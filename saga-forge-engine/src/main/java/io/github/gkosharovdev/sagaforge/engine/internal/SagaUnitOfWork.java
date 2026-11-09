package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.HandedOffEvents;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
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
 * <h2>Failures (transitional, US1)</h2>
 * <p>Any exception, a failure {@linkplain #failed(Throwable) reported} by the command gateway (even one the step
 * caught), or a rollback-only transaction found before the write rolls the transaction back, discards queue,
 * buffer and pending entries, logs {@code saga.uow.rolledBack} and rethrows. T052 replaces the rethrow with the
 * {@code FAILED} write; T073 adds the instance lock and the conflict path; T084 the {@code IN_TRANSACTION} handoff
 * between the write and the commit, and handoff-failure collection.</p>
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
    private String currentStep;
    private boolean ended;
    private Throwable firstFailure;

    SagaUnitOfWork(Trigger trigger, Collaborators with) {
        this.trigger = trigger;
        this.with = with;
        this.settings = with.settings();
        this.unitOfWorkId = settings.sources().newUnitOfWorkId();
        this.correlation = Correlation.of(trigger.event()).forSaga(trigger.saga().typeName())
                .instance(trigger.sagaId()).inUnitOfWork(unitOfWorkId);
        this.lifecycle = new EngineSagaLifecycle(trigger.saga(), settings.storage(), settings.sources());
        this.currentStep = trigger.step().name();
    }

    /** Runs this unit of work to its commit, or to a result that ran nothing. */
    SagaHandlingResult run() {
        LocalTransaction transaction = settings.transactions().begin();
        try {
            Optional<SagaHandlingResult> notRunnable = lockInstance(transaction);
            if (notRunnable.isPresent()) {
                return notRunnable.get();
            }
            runSteps();
            commit(transaction);
        } catch (RuntimeException | Error failure) {
            abandon(transaction, failure);
            throw failure;
        }
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
        settings.storage().save(write.instance(), write.entries());
        transaction.commit();
    }

    private void abandon(LocalTransaction transaction, Throwable failure) {
        queue.clear();
        recorded.clear();
        LocalTransactions.rollBackAfter(settings.transactions(), transaction, failure);
        with.diagnostics().unitOfWorkRolledBack(correlation, currentStep, failure);
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
        Object saga = settings.sagaObjects().map(provider -> provider.newInstance(type))
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
