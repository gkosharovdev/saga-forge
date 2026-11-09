package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.HandedOffEvents;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import io.github.gkosharovdev.sagaforge.engine.internal.InstanceLocks.Lease;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Injection;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Step;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One saga-scoped unit of work: one triggering event, one saga instance, one host transaction (002
 * contracts/saga-unit-of-work.md made concrete by contracts/contract-deltas.md; data-model.md "Saga-scoped unit of
 * work, step by step").
 *
 * <h2>{@link #run()}</h2>
 * <ol>
 *   <li>{@code begin()}; {@code StorageProvider.findByIdAndLock(sagaId)} as the first storage call. A missing
 *       instance is {@code UNMATCHED}, a terminal one {@code UNEXPECTED}, a still-{@code CREATED} one reached by a
 *       non-start step {@code NOT_HANDLED}; each rolls back and runs nothing (rows 2, 3).</li>
 *   <li>Loads the instance's {@code SagaContext} values into an {@link EngineSagaContext}, creates a fresh saga
 *       object ({@code SagaObjectProvider}, else the no-argument constructor) and injects its {@code @SagaId},
 *       {@code SagaContext} and {@code CommandGateway} fields (row 4, FR-009).</li>
 *   <li>Binds itself as the thread's recorder and runs the triggered step, then every queued event in recording
 *       order. After each step {@code recordStepCompleted}; at the first {@code @EndSaga} step {@code complete} and
 *       stop; at an empty queue {@code advance} (row 5).</li>
 *   <li>Saves the instance with the entries the lifecycle produced, commits, and hands every event not run
 *       in-process to the {@code EventHandoff} as one batch, after the commit (rows 6, 8).</li>
 * </ol>
 *
 * <h2>Recording ({@link #record(Object)})</h2>
 * <p>Each event adds {@code (@DomainEvent.aggregate, @AggregateIdentifier value)} to the running step's touched
 * aggregates. It is queued if it triggers a non-start step of this saga type and carries this instance's
 * association value for that step's property; otherwise it is buffered (FR-008, FR-017). Events still queued when
 * an {@code @EndSaga} step ends the run are handed off with the buffered ones, in recording order.</p>
 *
 * <h2>Failures (data-model.md row 9)</h2>
 * <p>Any exception from loading the context record, creating the saga object, a step, a handler (a failure
 * {@linkplain #failed(Throwable) reported} by the command gateway counts even if the step caught it), the instance
 * write or the commit, and a rollback-only transaction found before the write, end the run: no further step runs,
 * the transaction is rolled back if still active, queue, buffer and pending entries are discarded, and
 * {@code saga.uow.rolledBack} is logged. The {@link FailedWrite} then records {@code FAILED} with a
 * {@link SagaFailure} naming the step running or last run (the {@code @EndSaga} step for a write or commit failure)
 * and the first reported cause, and the result is {@code FAILED}. A failure of the {@code FAILED} write propagates,
 * as does a failure of the locked read itself (store unavailable): no outcome was recorded (FR-005).</p>
 * <h2>Conflict and the instance lock (FR-025, FR-026, data-model.md rows 8 to 10)</h2>
 * <p>A {@link SagaConcurrencyConflictException} from the instance write or the commit is no saga failure: the run
 * rolls back and discards like a failure, writes no {@code FAILED}, logs {@code saga.uow.conflict} and reports
 * {@code NOT_HANDLED} with its {@code unitOfWorkId}. The in-process instance lock handed in by the router is released
 * after the commit and before the handoff, after the {@code FAILED} write, or after rolling back a run that ran
 * nothing or conflicted.</p>
 * <p>T084 adds the {@code IN_TRANSACTION} handoff between the write and the commit, and handoff-failure
 * collection.</p>
 *
 * <p>Not thread-safe: confined to the thread that called {@code receive}.</p>
 */
final class SagaUnitOfWork implements UnitOfWork {

    /** What triggered this unit of work, for which instance. */
    record Trigger(SagaModel saga, Step step, Object event, String sagaId, boolean started) {
    }

    /** Collaborators shared by every unit of work of one engine. */
    record Collaborators(EngineSettings settings, CommandGateway gateway, Diagnostics diagnostics) {
    }

    /** A recorded event and whether a step of this instance has run it. */
    private static final class Recorded {

        private final Object event;
        private boolean ranInProcess;

        private Recorded(Object event) {
            this.event = event;
        }
    }

    /** An event waiting to run a step of this instance. */
    private record Queued(Step step, Recorded recorded) {
    }

    private final Trigger trigger;
    private final EngineSettings settings;
    private final Collaborators with;
    private final String unitOfWorkId;
    private final Correlation correlation;
    private final EngineSagaLifecycle lifecycle;
    private final List<Recorded> recorded = new ArrayList<>();
    private final Deque<Queued> queue = new ArrayDeque<>();
    private final List<String> stepsRun = new ArrayList<>();
    private List<AggregateReference> touched = new ArrayList<>();
    private Map<String, String> associations = Map.of();
    private final Lease instanceLease;
    private String currentStep;
    private boolean ended;
    private boolean writing;
    private Throwable firstFailure;

    /**
     * @param instanceLease the in-process lock of the instance, held by the caller; this unit of work releases it
     *                      after its commit (before the handoff), after its {@code FAILED} write, or after rolling
     *                      back a run that ran nothing or conflicted
     */
    SagaUnitOfWork(Trigger trigger, Collaborators with, Lease instanceLease) {
        this.trigger = trigger;
        this.with = with;
        this.instanceLease = instanceLease;
        this.settings = with.settings();
        this.unitOfWorkId = settings.sources().newUnitOfWorkId();
        this.correlation = Correlation.of(trigger.event()).forSaga(trigger.saga().typeName())
                .instance(trigger.sagaId()).inUnitOfWork(unitOfWorkId);
        this.lifecycle = new EngineSagaLifecycle(trigger.saga(), settings.storage(), settings.sources());
        this.currentStep = trigger.step().name();
    }

    /**
     * Runs this unit of work to its commit, to its {@code FAILED} write, or to a result that ran nothing.
     *
     * @throws RuntimeException if the locked read or the {@code FAILED} write fails; no outcome was recorded
     */
    SagaHandlingResult run() {
        LocalTransaction transaction = settings.transactions().begin();
        Optional<SagaHandlingResult> notRunnable = lockInstanceOrRollBack(transaction);
        if (notRunnable.isPresent()) {
            return notRunnable.get();
        }
        try {
            runSteps();
            commit(transaction);
        } catch (SagaConcurrencyConflictException conflict) {
            return writing ? conflicted(transaction, conflict) : failed(transaction, conflict);
        } catch (RuntimeException | Error failure) {
            return failed(transaction, failure);
        }
        instanceLease.close();
        with.diagnostics().unitOfWorkCommitted(correlation, outcome(), stepsRun);
        handOffBuffered();
        return committedResult();
    }

    @Override
    public void record(Object domainEvent) {
        AggregateReference reference = touchedAggregateOf(domainEvent);
        touched.add(reference);
        Recorded entry = new Recorded(domainEvent);
        recorded.add(entry);
        inProcessStepFor(domainEvent).ifPresent(step -> queue.add(new Queued(step, entry)));
    }

    @Override
    public void failed(Throwable failure) {
        if (firstFailure == null) {
            firstFailure = failure;
        }
    }

    /** {@link #lockInstance}; a failure of the locked read rolls back and propagates, recording nothing. */
    private Optional<SagaHandlingResult> lockInstanceOrRollBack(LocalTransaction transaction) {
        try {
            return lockInstance(transaction);
        } catch (RuntimeException | Error lockFailure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, lockFailure);
            throw lockFailure;
        }
    }

    private Optional<SagaHandlingResult> lockInstance(LocalTransaction transaction) {
        Optional<SagaInstance> locked = settings.storage().findByIdAndLock(trigger.sagaId());
        if (locked.isEmpty()) {
            return notRun(transaction, unmatched());
        }
        SagaInstance instance = locked.get();
        if (EngineSagaLifecycle.isTerminal(instance.status())) {
            return notRun(transaction, unexpected());
        }
        if (instance.status() == SagaStatus.CREATED && !trigger.step().start()) {
            return notRun(transaction, notStarted());
        }
        lifecycle.load(instance);
        associations = instance.associations();
        return Optional.empty();
    }

    private void runSteps() {
        Object saga = newSagaObject();
        EngineSagaContext context = new EngineSagaContext(trigger.sagaId(), settings.contextStore(),
                settings.sources().clock());
        trigger.saga().inject(saga, new Injection(trigger.sagaId(), context, with.gateway()));
        queue.add(new Queued(trigger.step(), new Recorded(trigger.event())));
        DomainEventScope.runWith(this, () -> runQueued(saga));
    }

    private void runQueued(Object saga) {
        while (!queue.isEmpty()) {
            Queued next = queue.poll();
            runStep(saga, next);
            if (next.step().end()) {
                lifecycle.complete(trigger.sagaId(), next.step().name());
                ended = true;
                return;
            }
        }
        lifecycle.advance(trigger.sagaId());
    }

    private void runStep(Object saga, Queued next) {
        next.recorded().ranInProcess = true;
        currentStep = next.step().name();
        stepsRun.add(currentStep);
        touched = new ArrayList<>();
        trigger.saga().run(next.step(), saga, next.recorded().event);
        failIfReported();
        lifecycle.recordStepCompleted(trigger.sagaId(), currentStep, touched);
    }

    private void commit(LocalTransaction transaction) {
        failIfReported();
        if (transaction.isRollbackOnly()) {
            throw new IllegalStateException("Unit of work " + unitOfWorkId + " of saga instance "
                    + trigger.sagaId() + " was marked rollback-only");
        }
        PendingWrite write = lifecycle.drain(trigger.sagaId());
        writing = true;
        settings.storage().save(write.instance(), write.entries());
        transaction.commit();
    }

    /**
     * Rolls back, discards everything this unit of work buffered, and records {@code FAILED}. The cause is the first
     * failure the command gateway reported, if any (a step may have caught it), else {@code thrown}.
     */
    private SagaHandlingResult failed(LocalTransaction transaction, Throwable thrown) {
        Throwable cause = firstFailure != null ? firstFailure : thrown;
        abandon(transaction, cause);
        SagaFailure failure = SagaFailure.of(currentStep, cause, settings.sources().now());
        SagaHandlingResult result = new FailedWrite(trigger.saga(), settings, with.diagnostics())
                .record(correlation, failure, cause, trigger.started());
        instanceLease.close();
        return result;
    }

    /**
     * A write-time conflict (FR-026, data-model.md row 10): rolls back and discards like a failure, but writes no
     * {@code FAILED} and reports {@code NOT_HANDLED}, so the transport can redeliver. No in-process retry: the steps'
     * context writes and non-transactional side effects already happened.
     */
    private SagaHandlingResult conflicted(LocalTransaction transaction, SagaConcurrencyConflictException conflict) {
        discard(transaction, conflict);
        instanceLease.close();
        with.diagnostics().unitOfWorkConflict(correlation);
        return new SagaHandlingResult(trigger.saga().typeName(), trigger.sagaId(), HandlingOutcome.NOT_HANDLED,
                trigger.started(), null, null, unitOfWorkId, List.of());
    }

    private void abandon(LocalTransaction transaction, Throwable failure) {
        discard(transaction, failure);
        with.diagnostics().unitOfWorkRolledBack(correlation, currentStep, failure);
    }

    /** Drops the queue and buffer and rolls back if the transaction is still active; pending entries die with it. */
    private void discard(LocalTransaction transaction, Throwable cause) {
        queue.clear();
        recorded.clear();
        LocalTransactions.rollBackAfter(settings.transactions(), transaction, cause);
    }

    private void handOffBuffered() {
        List<Object> buffered = recorded.stream().filter(entry -> !entry.ranInProcess).map(entry -> entry.event)
                .toList();
        if (!buffered.isEmpty()) {
            settings.handoff().handOff(new HandedOffEvents(unitOfWorkId, trigger.saga().typeName(),
                    trigger.sagaId(), buffered));
        }
    }

    private Object newSagaObject() {
        Class<?> type = trigger.saga().type();
        Object saga = settings.sagaObjects()
                .filter(provider -> provider.supports(type))
                .map(provider -> provider.newInstance(type))
                .orElseGet(trigger.saga()::newInstance);
        if (!type.isInstance(saga)) {
            throw new IllegalStateException("SagaObjectProvider returned " + saga + " for @Saga class "
                    + type.getName());
        }
        return saga;
    }

    private AggregateReference touchedAggregateOf(Object domainEvent) {
        try {
            DomainEvent annotation = domainEvent.getClass().getAnnotation(DomainEvent.class);
            if (annotation == null) {
                throw new IllegalArgumentException(domainEvent.getClass().getName() + " is not annotated @DomainEvent");
            }
            return AggregateReference.of(annotation.aggregate(), Reflection.aggregateIdentifier(domainEvent));
        } catch (RuntimeException invalid) {
            failed(invalid);
            throw invalid;
        }
    }

    private Optional<Step> inProcessStepFor(Object domainEvent) {
        return trigger.saga().stepFor(domainEvent.getClass())
                .filter(step -> !step.start())
                .filter(step -> correlates(step, domainEvent));
    }

    private boolean correlates(Step step, Object domainEvent) {
        String expected = associations.get(step.associationProperty());
        return expected != null && trigger.saga().associationValue(step, domainEvent)
                .filter(expected::equals)
                .isPresent();
    }

    private void failIfReported() {
        if (firstFailure != null) {
            throw UnitOfWork.propagate(firstFailure);
        }
    }

    private Optional<SagaHandlingResult> notRun(LocalTransaction transaction, SagaHandlingResult result) {
        transaction.rollback();
        instanceLease.close();
        return Optional.of(result);
    }

    private SagaHandlingResult unmatched() {
        with.diagnostics().eventUnmatched(correlation.instance(null).inUnitOfWork(null), currentStep);
        return resultWithoutUnitOfWork(HandlingOutcome.UNMATCHED, null);
    }

    private SagaHandlingResult unexpected() {
        with.diagnostics().eventUnexpected(correlation.inUnitOfWork(null), currentStep);
        return resultWithoutUnitOfWork(HandlingOutcome.UNEXPECTED, trigger.sagaId());
    }

    private SagaHandlingResult notStarted() {
        with.diagnostics().unitOfWorkNotStarted(correlation.inUnitOfWork(null));
        return resultWithoutUnitOfWork(HandlingOutcome.NOT_HANDLED, trigger.sagaId());
    }

    private SagaHandlingResult resultWithoutUnitOfWork(HandlingOutcome outcome, String sagaId) {
        return new SagaHandlingResult(trigger.saga().typeName(), sagaId, outcome, trigger.started(), null, null,
                null, List.of());
    }

    private HandlingOutcome outcome() {
        return ended ? HandlingOutcome.COMPLETED : HandlingOutcome.WAITING;
    }

    private SagaHandlingResult committedResult() {
        return new SagaHandlingResult(trigger.saga().typeName(), trigger.sagaId(), outcome(), trigger.started(),
                ended ? currentStep : null, null, unitOfWorkId, List.of());
    }
}
