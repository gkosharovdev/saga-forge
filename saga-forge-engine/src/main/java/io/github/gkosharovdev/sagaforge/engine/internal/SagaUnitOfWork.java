package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Injection;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Step;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One saga-scoped unit of work: one triggering event, one saga instance, one host transaction (002
 * contracts/saga-unit-of-work.md made concrete by contracts/contract-deltas.md; data-model.md "Saga-scoped unit of
 * work, step by step").
 *
 * <h2>{@link #run()}</h2>
 * <ol>
 *   <li>{@code begin()}; {@code StorageProvider.findByIdAndLock(sagaId)} as the first storage call. A missing
 *       instance is {@code UNMATCHED}; a {@code COMPENSATING} one rolls back and returns {@link Compensating}, so the
 *       loop resumes its compensations (006 research §10, checked before the duplicate check); one whose triggering
 *       (non-start) step already completed is {@code DUPLICATE} (005 FR-006), a terminal one {@code UNEXPECTED}, a
 *       still-{@code CREATED} one reached by a non-start step {@code NOT_HANDLED}; each rolls back and runs nothing
 *       (rows 2, 3).</li>
 *   <li>Loads the instance's {@code SagaContext} values into an {@link EngineSagaContext}, creates a fresh saga
 *       object ({@code SagaObjectProvider}, else the no-argument constructor) and injects its {@code @SagaId},
 *       {@code SagaContext} and {@code CommandGateway} fields (row 4, FR-009).</li>
 *   <li>Binds itself as the thread's recorder and runs the triggered step, then every queued event in recording
 *       order. After each step {@code recordStepCompleted}; at the first {@code @EndSaga} step {@code complete} and
 *       stop; at the first {@code @StartCompensation} step {@code startCompensation} and stop (006 FR-002, the
 *       committed result then carries {@code compensationStarted}); at an empty queue {@code advance} (row 5).</li>
 *   <li>Saves the instance with the entries the lifecycle produced, commits, and hands every event not run
 *       in-process to the {@code Outbox} as one batch, after the commit (rows 6, 8).</li>
 * </ol>
 *
 * <h2>Recording ({@link #record(Object)})</h2>
 * <p>Each event adds {@code (@DomainEvent.aggregate, @AggregateIdentifier value)} to the running step's touched
 * aggregates. It is queued if it triggers a non-start step of this saga type and carries this instance's
 * association value for that step's property; otherwise it is buffered (FR-008, FR-017). Events still queued when
 * an {@code @EndSaga} step ends the run go to the outbox with the buffered ones, in recording order.</p>
 *
 * <h2>Failures (data-model.md row 9; 005 contracts/attempt-loop.md)</h2>
 * <p>Any exception from loading the context record, creating the saga object, a step, a handler (a failure
 * {@linkplain #failed(Throwable) reported} by the command gateway counts even if the step caught it), the instance
 * write or the commit, and a rollback-only transaction found before the write, end the run: no further step runs,
 * the transaction is rolled back if still active, queue, buffer and pending entries are discarded, and
 * {@code saga.uow.rolledBack} is logged. The result is {@link Failed}, naming the step running or last run (the
 * {@code @EndSaga} step for a write or commit failure) and the first reported cause; the {@link AttemptLoop}
 * classifies it and either retries or writes {@code FAILED}. A failure of the locked read itself (store
 * unavailable) propagates: no outcome was recorded (FR-005). Since 005 one {@code SagaUnitOfWork} is one attempt:
 * steps it completes record its attempt number, and its commit clears a retry claimed for its trigger step.</p>
 * <h2>Conflict and the instance lock (FR-025, FR-026, data-model.md rows 8 to 10)</h2>
 * <p>A {@link SagaConcurrencyConflictException} from the instance write or the commit is no saga failure: the run
 * rolls back and discards like a failure, writes no {@code FAILED}, logs {@code saga.uow.conflict} and reports
 * {@code NOT_HANDLED} with its {@code unitOfWorkId}. The in-process instance lock is the {@link AttemptLoop}'s: held
 * across all attempts of one handling call and released after the last commit (before the outbox), after the
 * {@code FAILED} write, or after a result that ran nothing.</p>
 * <h2>Outbox (contracts/event-handoff.md, FR-029 to FR-031) and reentrancy (FR-007)</h2>
 * <p>The thread is marked "unit of work running" ({@link ReentrancyGuard}) from {@code begin()} until the commit or
 * rollback (and any {@code FAILED} write) has finished. With an {@code IN_TRANSACTION} outbox the non-empty batch is
 * stored in the outbox after the instance write and before the commit, inside the transaction, and its failure fails the unit
 * of work naming the last step run. With {@code AFTER_COMMIT} it goes to the outbox after the commit, the marker cleared and
 * the lock released; its failure is logged ({@code saga.outbox.failed}) and returned in the result's
 * {@code outboxFailures}, and the committed instance stays as it is.</p>
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

    /** How one attempt ended, for the {@link AttemptLoop} (005 contracts/attempt-loop.md). */
    sealed interface AttemptResult {
    }

    /** Nothing more to do: the attempt ran nothing, or lost a write-time conflict. The transaction is closed. */
    record Finished(SagaHandlingResult result) implements AttemptResult {
    }

    /**
     * The attempt committed. {@link #store()} runs the {@code AFTER_COMMIT} outbox and returns the result; the
     * caller releases the instance lock first.
     */
    record Committed(java.util.function.Supplier<SagaHandlingResult> afterRelease, boolean compensationStarted,
            String unitOfWorkId) implements AttemptResult {

        SagaHandlingResult store() {
            return afterRelease.get();
        }
    }

    /** The attempt rolled back: {@code step} was running or last run, {@code cause} is the failure to classify. */
    record Failed(String step, Throwable cause, Correlation correlation, int attempt) implements AttemptResult {
    }

    /**
     * The first attempt of a handling call found a retry claimed for its trigger step by an earlier call; it rolled
     * back and ran nothing, so the loop can claim the next attempt first (research §9).
     */
    record Resume(PendingRetry retry, Correlation correlation) implements AttemptResult {
    }

    /**
     * The locked instance is {@code COMPENSATING}: the event's step did not run and the attempt rolled back, so the
     * loop can resume the remaining compensations (006 research §10).
     */
    record Compensating(Correlation correlation) implements AttemptResult {
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
    private final List<OutgoingCommand> outboxCommands = new ArrayList<>();
    private final Deque<Queued> queue = new ArrayDeque<>();
    private final List<String> stepsRun = new ArrayList<>();
    private List<AggregateReference> touched = new ArrayList<>();
    private Map<String, String> associations = Map.of();
    private final boolean firstOfCall;
    private final CommandIds commandIds;
    private final Set<CommandId> committedCommands = new HashSet<>();
    private final Set<String> completedSteps = new HashSet<>();
    private List<CommandId> handledInStep = new ArrayList<>();
    private final int attempt;
    private String currentStep;
    private boolean ended;
    private boolean compensationStarted;
    private boolean writing;
    private Throwable firstFailure;
    private CompensationTrigger lifecycleTrigger;

    /**
     * @param attempt     the attempt this unit of work is, counting the first as 1; recorded on the steps it completes
     * @param firstOfCall whether this is the first attempt of the handling call, which resumes a retry claimed by an
     *                    earlier call instead of running
     */
    SagaUnitOfWork(Trigger trigger, Collaborators with, int attempt, boolean firstOfCall) {
        this.trigger = trigger;
        this.with = with;
        this.attempt = attempt;
        this.firstOfCall = firstOfCall;
        this.settings = with.settings();
        this.unitOfWorkId = settings.sources().newUnitOfWorkId();
        this.correlation = Correlation.of(trigger.event()).forSaga(trigger.saga().typeName())
                .instance(trigger.sagaId()).inUnitOfWork(unitOfWorkId);
        this.lifecycle = new EngineSagaLifecycle(trigger.saga(), settings.storage(), settings.sources());
        this.currentStep = trigger.step().name();
        this.commandIds = new CommandIds(trigger.sagaId());
    }

    /**
     * Runs this attempt to its commit, to its rollback, or to a result that ran nothing. Writes no {@code FAILED}
     * and releases no lock: both are the {@link AttemptLoop}'s.
     *
     * @throws RuntimeException if the locked read fails; nothing was recorded
     */
    AttemptResult run() {
        LocalTransaction transaction = settings.transactions().begin();
        ReentrancyGuard.enter();
        try {
            Optional<AttemptResult> notRunnable = lockInstanceOrRollBack(transaction);
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
        } finally {
            ReentrancyGuard.exit();
        }
        with.diagnostics().unitOfWorkCommitted(correlation, outcome(), stepsRun);
        if (compensationStarted) {
            with.diagnostics().compensationStarted(correlation, lifecycleTrigger);
        }
        return new Committed(() -> committedResult(storeAfterCommit()), compensationStarted, unitOfWorkId);
    }

    /** The id of this attempt's unit of work. */
    String unitOfWorkId() {
        return unitOfWorkId;
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

    @Override
    public CommandId nextCommandId(Class<?> commandType) {
        return commandIds.next(commandType);
    }

    @Override
    public boolean alreadyHandled(CommandId id) {
        return committedCommands.contains(id);
    }

    @Override
    public void commandHandled(CommandId id) {
        handledInStep.add(id);
    }

    @Override
    public void recordOutboxCommand(OutgoingCommand command) {
        outboxCommands.add(command);
    }

    /** {@link #lockInstance}; a failure of the locked read rolls back and propagates, recording nothing. */
    private Optional<AttemptResult> lockInstanceOrRollBack(LocalTransaction transaction) {
        try {
            return lockInstance(transaction);
        } catch (RuntimeException | Error lockFailure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, lockFailure);
            throw lockFailure;
        }
    }

    private Optional<AttemptResult> lockInstance(LocalTransaction transaction) {
        Optional<SagaInstance> locked = settings.storage().findByIdAndLock(trigger.sagaId());
        if (locked.isEmpty()) {
            return notRun(transaction, unmatched());
        }
        SagaInstance instance = locked.get();
        if (instance.status() == SagaStatus.COMPENSATING) {
            transaction.rollback();
            return Optional.of(new Compensating(correlation.inUnitOfWork(null)));
        }
        if (!trigger.step().start() && isCompleted(instance, trigger.step().name())) {
            return notRun(transaction, duplicate());
        }
        if (EngineSagaLifecycle.isTerminal(instance.status())) {
            return notRun(transaction, unexpected());
        }
        if (instance.status() == SagaStatus.CREATED && !trigger.step().start()) {
            return notRun(transaction, notStarted());
        }
        PendingRetry claimed = instance.pendingRetry();
        if (firstOfCall && claimed != null && claimed.triggerStep().equals(trigger.step().name())) {
            transaction.rollback();
            return Optional.of(new Resume(claimed, correlation));
        }
        lifecycle.load(instance);
        associations = instance.associations();
        instance.steps().values().stream().map(StepRecord::handledCommands).forEach(committedCommands::addAll);
        instance.steps().forEach((name, step) -> {
            if (step.status() == StepStatus.COMPLETED) {
                completedSteps.add(name);
            }
        });
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
            if (completedSteps.contains(next.step().name())) {
                // A repeated event for a step already completed: not run, stored in the outbox like any non-queued event.
                with.diagnostics().eventDuplicate(correlation, next.step().name());
                continue;
            }
            runStep(saga, next);
            if (next.step().end()) {
                lifecycle.complete(trigger.sagaId(), next.step().name());
                ended = true;
                return;
            }
            if (next.step().startsCompensation()) {
                lifecycleTrigger = CompensationTrigger.failureOutcome(next.step().name(),
                        next.recorded().event.getClass().getName(), settings.sources().now());
                lifecycle.startCompensation(trigger.sagaId(), lifecycleTrigger);
                compensationStarted = true;
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
        handledInStep = new ArrayList<>();
        commandIds.startStep(currentStep);
        trigger.saga().run(next.step(), saga, next.recorded().event);
        failIfReported();
        lifecycle.recordStepCompleted(trigger.sagaId(), currentStep, touched, attempt, handledInStep);
        completedSteps.add(currentStep);
    }

    private void commit(LocalTransaction transaction) {
        failIfReported();
        if (transaction.isRollbackOnly()) {
            throw new IllegalStateException("Unit of work " + unitOfWorkId + " of saga instance "
                    + trigger.sagaId() + " was marked rollback-only");
        }
        lifecycle.clearPendingRetry(trigger.step().name());
        PendingWrite write = lifecycle.drain(trigger.sagaId());
        writing = true;
        settings.storage().save(write.instance(), write.entries());
        writing = false;
        storeInTransaction();
        writing = true;
        transaction.commit();
    }

    /**
     * Rolls back and discards everything this unit of work buffered. The cause is the first failure the command
     * gateway reported, if any (a step may have caught it), else {@code thrown}; the loop classifies it.
     */
    private AttemptResult failed(LocalTransaction transaction, Throwable thrown) {
        Throwable cause = firstFailure != null ? firstFailure : thrown;
        abandon(transaction, cause);
        return new Failed(currentStep, cause, correlation, attempt);
    }

    /**
     * A write-time conflict (FR-026, data-model.md row 10): rolls back and discards like a failure, but writes no
     * {@code FAILED} and reports {@code NOT_HANDLED}, so the transport can redeliver. No in-process retry: the steps'
     * context writes and non-transactional side effects already happened.
     */
    private AttemptResult conflicted(LocalTransaction transaction, SagaConcurrencyConflictException conflict) {
        discard(transaction, conflict);
        with.diagnostics().unitOfWorkConflict(correlation);
        return new Finished(new SagaHandlingResult(trigger.saga().typeName(), trigger.sagaId(),
                HandlingOutcome.NOT_HANDLED, trigger.started(), null, null, unitOfWorkId, List.of()));
    }

    private void abandon(LocalTransaction transaction, Throwable failure) {
        discard(transaction, failure);
        with.diagnostics().unitOfWorkRolledBack(correlation, currentStep, failure);
    }

    /** Drops the queue and buffer and rolls back if the transaction is still active; pending entries die with it. */
    private void discard(LocalTransaction transaction, Throwable cause) {
        queue.clear();
        recorded.clear();
        outboxCommands.clear();
        LocalTransactions.rollBackAfter(settings.transactions(), transaction, cause);
    }

    /**
     * {@code IN_TRANSACTION}: hands the batch off after the instance write and before the commit, inside the unit of
     * work's transaction; a failure fails the unit of work like any other (contracts/event-handoff.md).
     */
    private void storeInTransaction() {
        if (settings.outboxTiming() == Outbox.Timing.IN_TRANSACTION) {
            buffered().ifPresent(settings.outbox()::store);
        }
    }

    /**
     * {@code AFTER_COMMIT}: hands the batch off after the commit and the lock release. A failure leaves the committed
     * instance untouched: it is logged and returned, with its suppressed exceptions, as the result's outbox
     * failures (FR-031).
     */
    private List<Throwable> storeAfterCommit() {
        if (settings.outboxTiming() != Outbox.Timing.AFTER_COMMIT) {
            OutboxCommitted.notify(settings, buffered(), with.diagnostics(), correlation);
            return List.of();
        }
        Optional<OutgoingMessages> batch = buffered();
        if (batch.isEmpty()) {
            return List.of();
        }
        try {
            settings.outbox().store(batch.get());
            return List.of();
        } catch (RuntimeException outboxFailure) {
            with.diagnostics().outboxFailed(correlation, batch.get().events().size(), outboxFailure);
            List<Throwable> failures = new ArrayList<>();
            failures.add(outboxFailure);
            failures.addAll(List.of(outboxFailure.getSuppressed()));
            return failures;
        }
    }

    /**
     * Every recorded event no step of this instance ran, in recording order, and every outbox command sent, in send
     * order; empty if there is neither.
     */
    private Optional<OutgoingMessages> buffered() {
        List<Object> events = recorded.stream().filter(entry -> !entry.ranInProcess).map(entry -> entry.event)
                .toList();
        return events.isEmpty() && outboxCommands.isEmpty() ? Optional.empty()
                : Optional.of(new OutgoingMessages(unitOfWorkId, trigger.saga().typeName(), trigger.sagaId(), events,
                        outboxCommands));
    }

    private Object newSagaObject() {
        Class<?> type = trigger.saga().type();
        // start() refused any saga type a registered provider does not support (G-ENG-21).
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

    private Optional<AttemptResult> notRun(LocalTransaction transaction, SagaHandlingResult result) {
        transaction.rollback();
        return Optional.of(new Finished(result));
    }

    private SagaHandlingResult unmatched() {
        with.diagnostics().eventUnmatched(correlation.instance(null).inUnitOfWork(null), currentStep);
        return resultWithoutUnitOfWork(HandlingOutcome.UNMATCHED, null);
    }

    private SagaHandlingResult unexpected() {
        with.diagnostics().eventUnexpected(correlation.inUnitOfWork(null), currentStep);
        return resultWithoutUnitOfWork(HandlingOutcome.UNEXPECTED, trigger.sagaId());
    }

    /** The triggering step already completed for the instance (005 FR-006): nothing runs, nothing is written. */
    private SagaHandlingResult duplicate() {
        with.diagnostics().eventDuplicate(correlation.inUnitOfWork(null), currentStep);
        return resultWithoutUnitOfWork(HandlingOutcome.DUPLICATE, trigger.sagaId());
    }

    static boolean isCompleted(SagaInstance instance, String stepName) {
        StepRecord step = instance.steps().get(stepName);
        return step != null && step.status() == StepStatus.COMPLETED;
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
        if (compensationStarted) {
            return HandlingOutcome.COMPENSATED;
        }
        return ended ? HandlingOutcome.COMPLETED : HandlingOutcome.WAITING;
    }

    private SagaHandlingResult committedResult(List<Throwable> outboxFailures) {
        return new SagaHandlingResult(trigger.saga().typeName(), trigger.sagaId(), outcome(), trigger.started(),
                ended ? currentStep : null, null, unitOfWorkId, outboxFailures);
    }
}
