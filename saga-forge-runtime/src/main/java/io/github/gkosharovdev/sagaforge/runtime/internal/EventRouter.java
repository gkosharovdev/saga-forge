package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.opentelemetry.api.common.Attributes;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.EngineSagaLifecycle.PendingWrite;
import io.github.gkosharovdev.sagaforge.runtime.internal.InstanceLocks.Lease;
import io.github.gkosharovdev.sagaforge.runtime.internal.SagaModel.Step;
import io.github.gkosharovdev.sagaforge.runtime.internal.SagaUnitOfWork.Collaborators;
import io.github.gkosharovdev.sagaforge.runtime.internal.SagaUnitOfWork.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@code EventReceiver.receive} algorithm (contracts/event-receiver.md, FR-001 to FR-006).
 *
 * <ol>
 *   <li><b>Validate first</b> (FR-006): a {@code null} or non-{@code @DomainEvent} event, or a missing or empty
 *       association value for any reacting step of any saga type, is an {@link IllegalArgumentException} before
 *       anything runs.</li>
 *   <li><b>Ignored</b>: no registered saga type has a step for the event's exact class → a report with no
 *       results ({@code saga.event.ignored}).</li>
 *   <li><b>Per saga type, in registration order</b>:
 *     <ul>
 *       <li>start step: {@code EngineSagaLifecycle.start} (an active instance with the value →
 *           {@code DUPLICATE_START}), the creation write in its own transaction ({@code CREATED} instance and
 *           entry, FR-012, FR-024), then the first unit of work ({@code started = true});</li>
 *       <li>other step: {@code findByAssociation} filtered to the saga type; one unit of work per active instance
 *           in the order returned, each committed or rolled back on its own; none → {@code UNMATCHED}; only
 *           terminal ones → one {@code UNEXPECTED} result for the most recently returned (FR-002 to FR-004).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h2>No outcome recorded (FR-005, contracts/event-receiver.md "Failure")</h2>
 * <p>When for a saga type or instance no outcome could be recorded (the store was unavailable in
 * {@code findByAssociation} or {@code findByIdAndLock}, the creation write failed, or the {@code FAILED} write
 * failed), the cause is collected and the router goes on with the remaining saga types and instances. Afterwards
 * it logs {@code saga.event.notHandled} and throws {@link EventHandlingException} carrying the results that were
 * recorded and every cause. A failed creation write creates nothing and has no result of its own.</p>
 *
 * <h2>Locks (FR-025, FR-027, FR-028, data-model.md "Locks")</h2>
 * <p>Always taken in the order duplicate-start → instance → storage. A start step holds the duplicate-start lock of
 * (saga type, association property, value) around the duplicate check and the creation write, and the new instance's
 * lock from before the creation write until its first unit of work releases it. A non-start step takes each
 * correlated active instance's lock in turn, one at a time, so the router never holds two instance locks and cannot
 * deadlock itself. Different instances never wait for each other. Both locks are in-process; the storage lock
 * ({@code findByIdAndLock}) serializes across processes.</p>
 *
 * <h2>Reentrancy (FR-007)</h2>
 * <p>{@code receive} first rejects a call made while an engine unit of work runs on the calling thread (from a step,
 * a handler or an {@code IN_TRANSACTION} outbox) with {@link IllegalStateException}, which also fails that unit of
 * work ({@link ReentrancyGuard}). A call from inside an {@code AFTER_COMMIT} outbox is accepted: the previous unit of
 * work has committed and released its lock, so this is a new one.</p>
 *
 * <p>Immutable; thread-safe if the host plug-ins are. Each call runs on the calling thread.</p>
 */
final class EventRouter {

    /** One saga type reacting to the event, through its step for the event's class. */
    private record Reaction(SagaModel saga, Step step, Object event, String associationValue) {

        Correlation correlation() {
            return Correlation.of(event).forSaga(saga.typeName());
        }
    }

    /** The duplicate-start lock's key: one saga type's start on one association value. */
    private record StartKey(String sagaType, String associationProperty, String associationValue) {
    }

    private final List<SagaModel> sagas;
    private final Collaborators with;
    private final EngineSettings settings;
    private final InstanceLocks startLocks = new InstanceLocks();
    private final InstanceLocks instanceLocks = new InstanceLocks();

    /**
     * @param sagas the registered saga types, in registration order
     * @param with  the collaborators every unit of work shares
     */
    EventRouter(List<SagaModel> sagas, Collaborators with) {
        this.sagas = List.copyOf(sagas);
        this.with = with;
        this.settings = with.settings();
    }

    HandlingReport receive(Object event) {
        ReentrancyGuard.requireNotRunning();
        requireDomainEvent(event);
        List<Reaction> reactions = reactionsTo(event);
        if (reactions.isEmpty()) {
            try (SagaInstrumentation.Traced span = with.instrumentation().start(SagaInstrumentation.EVENT_IGNORED)) {
                span.set(SagaInstrumentation.EVENT_TYPE, event.getClass().getName())
                        .set(SagaInstrumentation.CONTENT, with.instrumentation().content(event));
                with.diagnostics().eventIgnored(event);
            }
            return new HandlingReport(event, List.of());
        }
        Attempts attempts = new Attempts();
        reactions.forEach(reaction -> handle(reaction, attempts));
        HandlingReport report = new HandlingReport(event, attempts.results);
        if (!attempts.failures.isEmpty()) {
            throw notHandled(event, report, attempts.failures);
        }
        return report;
    }

    private List<Reaction> reactionsTo(Object event) {
        List<Reaction> reactions = new ArrayList<>();
        for (SagaModel saga : sagas) {
            saga.stepFor(event.getClass()).ifPresent(step -> reactions.add(
                    new Reaction(saga, step, event, requireAssociationValue(saga, step, event))));
        }
        return reactions;
    }

    private void handle(Reaction reaction, Attempts attempts) {
        if (reaction.step().start()) {
            attempts.attempt(() -> traced(reaction, null, () -> start(reaction))).ifPresent(attempts.results::add);
        } else {
            attempts.attempt(() -> correlatedInstances(reaction))
                    .ifPresent(correlated -> resume(reaction, correlated, attempts));
        }
    }

    /**
     * Under the duplicate-start lock: the duplicate check, then the new instance's lock, then the creation write; the
     * duplicate-start lock is released before the first unit of work runs (FR-027, data-model.md "Locks").
     */
    private SagaHandlingResult start(Reaction reaction) {
        Lease instanceLease;
        String sagaId;
        try (Lease startLease = startLocks.acquire(startKeyOf(reaction))) {
            EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(reaction.saga(), settings.storage(),
                    settings.sources());
            try {
                sagaId = lifecycle.start(reaction.saga().type(), reaction.step().associationProperty(),
                        reaction.associationValue()).sagaId();
            } catch (DuplicateSagaInstanceException duplicate) {
                return duplicateStart(reaction);
            }
            instanceLease = instanceLocks.acquire(sagaId);
            commitCreationOrRelease(lifecycle.drain(sagaId), instanceLease);
        }
        return runUnitOfWork(reaction, sagaId, true, instanceLease);
    }

    private void commitCreationOrRelease(PendingWrite creation, Lease instanceLease) {
        try {
            commitCreation(creation);
        } catch (RuntimeException | Error failure) {
            instanceLease.close();
            throw failure;
        }
    }

    private static StartKey startKeyOf(Reaction reaction) {
        return new StartKey(reaction.saga().typeName(), reaction.step().associationProperty(),
                reaction.associationValue());
    }

    private void commitCreation(PendingWrite creation) {
        LocalTransaction transaction = settings.transactions().begin();
        try {
            settings.storage().save(creation.instance(), creation.entries());
            transaction.commit();
        } catch (RuntimeException | Error failure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, failure);
            throw failure;
        }
    }

    /** The instances of the reaction's saga type that carry the event's association value, in storage order. */
    private List<SagaInstance> correlatedInstances(Reaction reaction) {
        return settings.storage()
                .findByAssociation(reaction.step().associationProperty(), reaction.associationValue()).stream()
                .filter(instance -> instance.sagaType().equals(reaction.saga().typeName()))
                .toList();
    }

    /**
     * One unit of work per active instance, each attempted on its own; else one DUPLICATE (the latest ended instance
     * completed the step), UNEXPECTED or UNMATCHED result.
     */
    private void resume(Reaction reaction, List<SagaInstance> correlated, Attempts attempts) {
        List<SagaInstance> active = correlated.stream()
                .filter(instance -> !EngineSagaLifecycle.isTerminal(instance.status()))
                .toList();
        if (!active.isEmpty()) {
            active.forEach(instance -> attempts.attempt(() -> traced(reaction, instance.sagaId(),
                            () -> runUnitOfWork(reaction, instance.sagaId(), false,
                                    instanceLocks.acquire(instance.sagaId()))))
                    .ifPresent(attempts.results::add));
        } else if (!correlated.isEmpty()) {
            SagaInstance latest = correlated.get(correlated.size() - 1);
            attempts.results.add(traced(reaction, latest.sagaId(),
                    () -> SagaUnitOfWork.isCompleted(latest, reaction.step().name())
                            ? duplicate(reaction, latest.sagaId())
                            : unexpected(reaction, latest.sagaId())));
        } else {
            attempts.results.add(traced(reaction, null, () -> unmatched(reaction)));
        }
    }

    /**
     * Runs the attempts of one unit of work holding {@code instanceLease} (005 {@link AttemptLoop}). The loop releases
     * the lease at the point data-model.md row 8 or 9 names; closing it here again is a no-op that only guards the
     * paths that raise.
     */
    private SagaHandlingResult runUnitOfWork(Reaction reaction, String sagaId, boolean started, Lease instanceLease) {
        try (instanceLease) {
            Trigger trigger = new Trigger(reaction.saga(), reaction.step(), reaction.event(), sagaId, started);
            return new AttemptLoop(with).run(trigger, instanceLease);
        }
    }

    /**
     * Runs {@code work} inside a {@code saga_forge.handle} span for one saga type or instance, and counts its outcome
     * ({@code specs/010-otel-observability} research §2, §4).
     */
    private SagaHandlingResult traced(Reaction reaction, String sagaId, Supplier<SagaHandlingResult> work) {
        SagaInstrumentation telemetry = with.instrumentation();
        try (SagaInstrumentation.Traced span = telemetry.start(SagaInstrumentation.HANDLE)) {
            span.set(SagaInstrumentation.SAGA_TYPE, reaction.saga().typeName())
                    .set(SagaInstrumentation.EVENT_TYPE, reaction.event().getClass().getName())
                    .set(SagaInstrumentation.SAGA_ID, sagaId)
                    .set(SagaInstrumentation.ASSOCIATION_VALUE,
                            telemetry.associationValue(reaction.associationValue()))
                    .set(SagaInstrumentation.CONTENT, telemetry.content(reaction.event()));
            SagaHandlingResult result;
            try {
                result = work.get();
            } catch (RuntimeException | Error failure) {
                span.fail(failure);
                throw failure;
            }
            span.set(SagaInstrumentation.SAGA_ID, result.sagaId())
                    .set(SagaInstrumentation.HANDLING_OUTCOME, result.outcome().name())
                    .set(SagaInstrumentation.SAGA_STARTED, result.started());
            if (result.failure() != null) {
                span.set(SagaInstrumentation.FAILURE_CLASSIFICATION, result.failure().classification() == null
                        ? null : result.failure().classification().name())
                        .failType(result.failure().causeType(), result.failure().message());
            }
            countOutcome(telemetry, span, result);
            return result;
        }
    }

    private static void countOutcome(SagaInstrumentation telemetry, SagaInstrumentation.Traced span,
            SagaHandlingResult result) {
        Attributes sagaType = Attributes.of(SagaInstrumentation.SAGA_TYPE, result.sagaType());
        telemetry.count(SagaInstrumentation.EVENT_HANDLED_COUNTER, sagaType.toBuilder()
                .put(SagaInstrumentation.HANDLING_OUTCOME, result.outcome().name()).build());
        if (result.started() && result.outcome() != HandlingOutcome.DUPLICATE_START) {
            telemetry.count(SagaInstrumentation.SAGA_STARTED_COUNTER, sagaType);
            span.event(SagaInstrumentation.SAGA_STARTED_COUNTER, Attributes.empty());
        }
        String ended = switch (result.outcome()) {
            case COMPLETED -> "completed:" + result.outcomeStep();
            case FAILED -> "failed";
            case COMPENSATED -> "compensated";
            case REQUIRES_INTERVENTION -> "requires_intervention";
            default -> null;
        };
        if (ended != null) {
            Attributes outcome = sagaType.toBuilder().put(SagaInstrumentation.SAGA_OUTCOME, ended).build();
            telemetry.count(SagaInstrumentation.SAGA_ENDED_COUNTER, outcome);
            span.event(SagaInstrumentation.SAGA_ENDED_COUNTER,
                    Attributes.of(SagaInstrumentation.SAGA_OUTCOME, ended));
        }
    }

    private SagaHandlingResult duplicateStart(Reaction reaction) {
        with.diagnostics().eventDuplicateStart(reaction.correlation(), reaction.step().associationProperty(),
                reaction.associationValue());
        return noUnitOfWork(reaction, HandlingOutcome.DUPLICATE_START, null);
    }

    /** Only ended instances correlate and the latest already completed the step (005 FR-006). */
    private SagaHandlingResult duplicate(Reaction reaction, String sagaId) {
        with.diagnostics().eventDuplicate(reaction.correlation().instance(sagaId), reaction.step().name());
        return noUnitOfWork(reaction, HandlingOutcome.DUPLICATE, sagaId);
    }

    private SagaHandlingResult unexpected(Reaction reaction, String sagaId) {
        with.diagnostics().eventUnexpected(reaction.correlation().instance(sagaId), reaction.step().name());
        return noUnitOfWork(reaction, HandlingOutcome.UNEXPECTED, sagaId);
    }

    private SagaHandlingResult unmatched(Reaction reaction) {
        with.diagnostics().eventUnmatched(reaction.correlation(), reaction.step().name());
        return noUnitOfWork(reaction, HandlingOutcome.UNMATCHED, null);
    }

    private EventHandlingException notHandled(Object event, HandlingReport report, List<RuntimeException> causes) {
        EventHandlingException failure = new EventHandlingException("No outcome could be recorded for "
                + causes.size() + " of the saga types or instances reacting to " + event.getClass().getName()
                + "; the event should be redelivered", report, List.<Throwable>copyOf(causes));
        with.diagnostics().eventNotHandled(event, failure);
        return failure;
    }

    private static SagaHandlingResult noUnitOfWork(Reaction reaction, HandlingOutcome outcome, String sagaId) {
        return new SagaHandlingResult(reaction.saga().typeName(), sagaId, outcome, false, null, null, null,
                List.of());
    }

    private static void requireDomainEvent(Object event) {
        if (event == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        if (!event.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException(event.getClass().getName() + " is not annotated @DomainEvent");
        }
    }

    private static String requireAssociationValue(SagaModel saga, Step step, Object event) {
        return saga.associationValue(step, event).orElseThrow(() -> new IllegalArgumentException(
                "Event " + event.getClass().getName() + " has no value for association property '"
                        + step.associationProperty() + "' of step " + step.name() + " of saga "
                        + saga.typeName()));
    }

    /**
     * The results recorded for one event and the failures for which none could be; confined to one
     * {@code receive} call.
     */
    private static final class Attempts {

        private final List<SagaHandlingResult> results = new ArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();

        /** Runs {@code work}; a {@link RuntimeException} is collected as a failure and yields empty. */
        <T> Optional<T> attempt(Supplier<T> work) {
            try {
                return Optional.of(work.get());
            } catch (RuntimeException failure) {
                failures.add(failure);
                return Optional.empty();
            }
        }
    }
}
