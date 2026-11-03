package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessSagaModel.Step;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaLifecycle;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One simulated saga-scoped unit of work of the saga test harness (contracts/saga-unit-of-work.md).
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <p>Created by {@link SagaTestHarness} for one triggering event and one saga instance (existing, or started
 * by the unit of work for an {@code @StartSaga} step). It is also the {@link DomainEventRecorder} bound through
 * {@link DomainEventScope#runWith(DomainEventRecorder, Runnable)} for the whole unit of work.</p>
 *
 * <h2>Run ({@link #run()})</h2>
 * <ol>
 *   <li><b>Begin</b>: snapshot every {@link Participant} (storage, aggregate store, snapshottable handlers).
 *       The {@code SagaContextStore} is not a participant.</li>
 *   <li>Start the instance ({@code SagaLifecycle.start}) for a start step, create the saga object and inject
 *       its {@code @SagaId}, {@code SagaContext} (write-through {@link HarnessSagaContext}) and
 *       {@code CommandGateway} fields.</li>
 *   <li><b>Steps</b>: run the triggered step, then every queued in-process event in recording order. An event
 *       recorded here is queued if its exact type triggers a non-start step of this saga and its
 *       {@code associationProperty} value equals the instance's association value for that property;
 *       otherwise it is held for after-commit dispatch. Every recorded event adds
 *       {@code (DomainEvent.aggregate, @AggregateIdentifier value)} to the running step's touched aggregates;
 *       {@code recordStepCompleted} is called after each step returns.</li>
 *   <li><b>Commit</b> at the first {@code @EndSaga} step ({@code complete(sagaId, step)}; queued events left
 *       undelivered are dispatched instead) or when the queue is empty ({@code advance(sagaId)}). The
 *       harness then calls {@link #dispatchPending()}.</li>
 *   <li><b>Rollback</b> on any exception, or when the unit of work was marked rollback-only (for example by
 *       {@link HarnessCommandGateway} for a handler failure the step caught): restore every participant,
 *       discard held events, then in a separate write record {@code FAILED} with
 *       {@code SagaFailure.of(currentStep, cause, now)}. If the instance was started by this unit of work, the
 *       write first re-creates its {@code CREATED} record, so a failed first unit of work leaves a
 *       {@code FAILED} instance.</li>
 * </ol>
 *
 * <p>Not thread-safe; one unit of work at a time per harness.</p>
 */
public final class HarnessUnitOfWork implements DomainEventRecorder {

    /** Something whose state is snapshotted at begin and restored on rollback. */
    record Participant(Supplier<Object> snapshot, Consumer<Object> restore) {
    }

    /** Collaborators shared by every unit of work of one harness. */
    record Collaborators(
            HarnessSagaLifecycle lifecycle,
            InMemoryStorageProvider storage,
            InMemorySagaContextStore contextStore,
            EventDispatcher dispatcher,
            HarnessCommandGateway gateway,
            HarnessEventLog log,
            Clock clock,
            List<Participant> participants) {
    }

    private static final class Recorded {
        private final Object event;
        private boolean deliveredInProcess;

        private Recorded(Object event) {
            this.event = event;
        }
    }

    private record Pending(Step step, Object event, Recorded recorded) {
    }

    private final HarnessSagaModel saga;
    private final Step triggeredStep;
    private final Object triggeringEvent;
    private final SagaInstance existing;
    private final String associationValue;
    private final Collaborators with;
    private final SagaLifecycle lifecycle;

    private final List<Recorded> recorded = new ArrayList<>();
    private final Deque<Pending> queue = new ArrayDeque<>();
    private final List<String> stepsRun = new ArrayList<>();
    private final List<Object> dispatched = new ArrayList<>();
    private List<AggregateReference> touched = new ArrayList<>();

    private SagaInstance started;
    private String sagaId;
    private Map<String, String> associations = Map.of();
    private String currentStep;
    private boolean ended;
    private Throwable rollbackOnlyCause;
    private boolean committed;
    private Throwable cause;
    private SagaFailure failure;

    /**
     * @param saga             the saga type's metadata
     * @param triggeredStep    the step the triggering event triggers
     * @param triggeringEvent  the delivered event
     * @param existing         the resolved instance, or {@code null} to start one ({@code triggeredStep} is the
     *                         start step)
     * @param associationValue the triggering event's association value
     */
    HarnessUnitOfWork(HarnessSagaModel saga, Step triggeredStep, Object triggeringEvent, SagaInstance existing,
            String associationValue, Collaborators with) {
        this.saga = saga;
        this.triggeredStep = triggeredStep;
        this.triggeringEvent = triggeringEvent;
        this.existing = existing;
        this.associationValue = associationValue;
        this.with = with;
        this.lifecycle = with.lifecycle();
        this.currentStep = triggeredStep.name();
    }

    /**
     * Marks this unit of work rollback-only; the first cause wins. It rolls back when the running step
     * returns, even if the step caught the exception.
     */
    public void markRollbackOnly(Throwable failureCause) {
        if (rollbackOnlyCause == null) {
            rollbackOnlyCause = failureCause;
        }
    }

    /** {@code true} once {@link #markRollbackOnly(Throwable)} was called. */
    public boolean isRollbackOnly() {
        return rollbackOnlyCause != null;
    }

    /** The saga id of this unit of work's instance, or {@code null} before the instance is started. */
    public String sagaId() {
        return sagaId;
    }

    @Override
    public void record(Object domainEvent) {
        AggregateReference reference;
        try {
            DomainEvent annotation = domainEvent.getClass().getAnnotation(DomainEvent.class);
            if (annotation == null) {
                throw new IllegalArgumentException(domainEvent.getClass().getName() + " is not annotated @DomainEvent");
            }
            reference = AggregateReference.of(annotation.aggregate(),
                    HarnessReflection.aggregateIdentifier(domainEvent));
        } catch (RuntimeException e) {
            markRollbackOnly(e);
            throw e;
        }
        touched.add(reference);
        Recorded entry = new Recorded(domainEvent);
        recorded.add(entry);
        Optional<Step> step = saga.stepFor(domainEvent.getClass());
        if (step.isPresent() && !step.get().start() && correlates(domainEvent, step.get())) {
            queue.add(new Pending(step.get(), domainEvent, entry));
        }
    }

    /**
     * Runs the unit of work to commit or rollback (including the separate {@code FAILED} write).
     *
     * @return {@code true} if it committed
     */
    boolean run() {
        List<Object> snapshots = with.participants().stream().map(p -> p.snapshot().get()).toList();
        try {
            DomainEventScope.runWith(this, this::runSteps);
            committed = true;
            with.log().add(Kind.COMMITTED, triggeringEvent, saga.type(), sagaId, null);
        } catch (Throwable thrown) {
            rollback(snapshots, thrown);
        }
        return committed;
    }

    /** Hands every recorded event not delivered in-process to the dispatcher, in recording order. */
    void dispatchPending() {
        if (!committed) {
            throw new IllegalStateException("unit of work did not commit");
        }
        for (Recorded entry : recorded) {
            if (!entry.deliveredInProcess) {
                with.log().add(Kind.DISPATCHED, entry.event, saga.type(), sagaId, null);
                dispatched.add(entry.event);
                with.dispatcher().dispatch(entry.event);
            }
        }
    }

    UnitOfWorkResult result() {
        return new UnitOfWorkResult(committed ? Outcome.COMMITTED : Outcome.ROLLED_BACK, triggeringEvent,
                saga.type(), sagaId, stepsRun, failure, cause, dispatched);
    }

    private void runSteps() {
        if (existing == null) {
            started = lifecycle.start(saga.type(), triggeredStep.associationProperty(), associationValue);
            sagaId = started.sagaId();
            associations = started.associations();
        } else {
            sagaId = existing.sagaId();
            associations = existing.associations();
        }
        with.log().add(Kind.DELIVERED, triggeringEvent, saga.type(), sagaId, triggeredStep.name());
        Object sagaObject = saga.instantiate(
                sagaId, new HarnessSagaContext(sagaId, with.contextStore(), with.clock()), with.gateway());
        queue.add(new Pending(triggeredStep, triggeringEvent, null));
        while (!queue.isEmpty()) {
            Pending next = queue.poll();
            if (next.recorded() != null) {
                next.recorded().deliveredInProcess = true;
                with.log().add(Kind.IN_PROCESS, next.event(), saga.type(), sagaId, next.step().name());
            }
            currentStep = next.step().name();
            stepsRun.add(currentStep);
            touched = new ArrayList<>();
            HarnessReflection.invoke(next.step().method(), sagaObject, next.event());
            throwIfRollbackOnly();
            lifecycle.recordStepCompleted(sagaId, currentStep, touched);
            if (next.step().end()) {
                lifecycle.complete(sagaId, currentStep);
                ended = true;
                break;
            }
        }
        if (!ended) {
            lifecycle.advance(sagaId);
        }
        throwIfRollbackOnly();
    }

    private void rollback(List<Object> snapshots, Throwable thrown) {
        List<Participant> participants = with.participants();
        for (int i = 0; i < participants.size(); i++) {
            participants.get(i).restore().accept(snapshots.get(i));
        }
        for (Recorded entry : recorded) {
            if (!entry.deliveredInProcess) {
                with.log().add(Kind.DISCARDED, entry.event, saga.type(), sagaId, null);
            }
        }
        queue.clear();
        cause = thrown;
        with.log().add(Kind.ROLLED_BACK, triggeringEvent, saga.type(), sagaId, currentStep);
        if (sagaId != null) {
            if (started != null && with.storage().findById(sagaId).isEmpty()) {
                with.storage().save(started);
            }
            failure = SagaFailure.of(currentStep, thrown, with.clock().instant());
            lifecycle.fail(sagaId, failure);
        }
    }

    private void throwIfRollbackOnly() {
        if (rollbackOnlyCause != null) {
            throw HarnessReflection.sneakyThrow(rollbackOnlyCause);
        }
    }

    private boolean correlates(Object event, Step step) {
        String expected = associations.get(step.associationProperty());
        if (expected == null) {
            return false;
        }
        return HarnessReflection.readProperty(event, step.associationProperty())
                .map(String::valueOf)
                .filter(expected::equals)
                .isPresent();
    }
}
