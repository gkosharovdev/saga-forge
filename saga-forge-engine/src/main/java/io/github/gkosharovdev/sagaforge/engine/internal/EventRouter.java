package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Step;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Collaborators;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Trigger;
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
 * <p>T072 adds the duplicate-start and instance locks, T086 the reentrancy check.</p>
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

    private final List<SagaModel> sagas;
    private final Collaborators with;
    private final EngineSettings settings;

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
        requireDomainEvent(event);
        List<Reaction> reactions = reactionsTo(event);
        if (reactions.isEmpty()) {
            with.diagnostics().eventIgnored(event);
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
            attempts.attempt(() -> start(reaction)).ifPresent(attempts.results::add);
        } else {
            attempts.attempt(() -> correlatedInstances(reaction))
                    .ifPresent(correlated -> resume(reaction, correlated, attempts));
        }
    }

    private SagaHandlingResult start(Reaction reaction) {
        EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(reaction.saga(), settings.storage(),
                settings.sources());
        SagaInstance created;
        try {
            created = lifecycle.start(reaction.saga().type(), reaction.step().associationProperty(),
                    reaction.associationValue());
        } catch (DuplicateSagaInstanceException duplicate) {
            return duplicateStart(reaction);
        }
        commitCreation(lifecycle.drain(created.sagaId()));
        return runUnitOfWork(reaction, created.sagaId(), true);
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

    /** One unit of work per active instance, each attempted on its own; else one UNEXPECTED or UNMATCHED result. */
    private void resume(Reaction reaction, List<SagaInstance> correlated, Attempts attempts) {
        List<SagaInstance> active = correlated.stream()
                .filter(instance -> !EngineSagaLifecycle.isTerminal(instance.status()))
                .toList();
        if (!active.isEmpty()) {
            active.forEach(instance -> attempts.attempt(() -> runUnitOfWork(reaction, instance.sagaId(), false))
                    .ifPresent(attempts.results::add));
        } else if (!correlated.isEmpty()) {
            attempts.results.add(unexpected(reaction, correlated.get(correlated.size() - 1).sagaId()));
        } else {
            attempts.results.add(unmatched(reaction));
        }
    }

    private SagaHandlingResult runUnitOfWork(Reaction reaction, String sagaId, boolean started) {
        Trigger trigger = new Trigger(reaction.saga(), reaction.step(), reaction.event(), sagaId, started);
        return new SagaUnitOfWork(trigger, with).run();
    }

    private SagaHandlingResult duplicateStart(Reaction reaction) {
        with.diagnostics().eventDuplicateStart(reaction.correlation(), reaction.step().associationProperty(),
                reaction.associationValue());
        return noUnitOfWork(reaction, HandlingOutcome.DUPLICATE_START, null);
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
