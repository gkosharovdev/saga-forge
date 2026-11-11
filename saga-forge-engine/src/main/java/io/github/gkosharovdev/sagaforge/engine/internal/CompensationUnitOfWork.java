package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Compensation;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Injection;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Collaborators;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One compensation in its own saga-scoped unit of work (specs/006-saga-compensation contracts/compensation-run.md,
 * research §7, §8; FR-009, FR-011, FR-012, FR-014).
 *
 * <p>{@link #run()}: {@code begin()}; {@code findByIdAndLock}; the instance must be {@code COMPENSATING} with a
 * compensation left to run, else it rolls back and reports {@link Stopped} with the status found. The next
 * compensation is the {@code NOT_ATTEMPTED} step completed last. A fresh saga object gets its {@code @SagaId},
 * {@code SagaContext} and {@code CommandGateway} fields, this unit of work is bound as the thread's recorder and the
 * {@code @SagaCompensation} method runs with the persisted trigger if it declares one. Its commands get 005
 * identities scoped to the compensation method's name. Events recorded by their handlers are buffered for the
 * outbox, never run in-process: no forward step runs while compensating. The step is then marked
 * {@code COMPENSATED} (and the instance {@code COMPENSATED} after the last one), saved and committed together with
 * the compensation's effects.</p>
 *
 * <p>A failure thrown by the compensation, or reported by the command gateway even if the compensation caught it,
 * rolls everything back and is reported as {@link Failed}; a write-time {@link SagaConcurrencyConflictException} as
 * {@link Conflicted}. A failure of the locked read propagates after the rollback. Outbox timing follows 004: with
 * {@code IN_TRANSACTION} inside the transaction, otherwise by {@link Committed#store()} after the caller released
 * the instance lock.</p>
 *
 * <p>Not thread-safe: confined to the thread that handles the triggering event.</p>
 */
final class CompensationUnitOfWork implements UnitOfWork {

    /** How the compensation unit of work ended. */
    sealed interface Result {
    }

    /**
     * The compensation of {@code step} committed; {@code instance} is the instance as written. {@link #store()}
     * runs the {@code AFTER_COMMIT} outbox and returns its failures.
     */
    record Committed(String step, SagaInstance instance, String unitOfWorkId,
            Supplier<List<Throwable>> afterRelease) implements Result {

        List<Throwable> store() {
            return afterRelease.get();
        }
    }

    /** The compensation of {@code step} failed with {@code cause}; everything was rolled back. */
    record Failed(String step, Throwable cause, Correlation correlation) implements Result {
    }

    /** The write lost a concurrency conflict; everything was rolled back and the instance stays as it was. */
    record Conflicted(String unitOfWorkId) implements Result {
    }

    /** Nothing ran: the locked instance was missing or no longer compensating ({@code status}, or {@code null}). */
    record Stopped(SagaStatus status) implements Result {
    }

    private final SagaModel saga;
    private final String sagaId;
    private final Collaborators with;
    private final EngineSettings settings;
    private final String unitOfWorkId;
    private final Correlation correlation;
    private final EngineSagaLifecycle lifecycle;
    private final CommandIds commandIds;
    private final List<Object> recorded = new ArrayList<>();
    private final List<OutgoingCommand> outboxCommands = new ArrayList<>();
    private String step;
    private boolean writing;
    private Throwable firstFailure;

    /**
     * @param saga   the saga type of the instance
     * @param sagaId the compensating instance
     * @param with   the engine's collaborators
     * @param about  the correlation of the handling call (event type and saga type set)
     */
    CompensationUnitOfWork(SagaModel saga, String sagaId, Collaborators with, Correlation about) {
        this.saga = saga;
        this.sagaId = sagaId;
        this.with = with;
        this.settings = with.settings();
        this.unitOfWorkId = settings.sources().newUnitOfWorkId();
        this.correlation = about.instance(sagaId).inUnitOfWork(unitOfWorkId);
        this.lifecycle = new EngineSagaLifecycle(saga, settings.storage(), settings.sources());
        this.commandIds = new CommandIds(sagaId);
    }

    /**
     * Runs the next compensation of the instance to its commit or rollback.
     *
     * @throws RuntimeException if the locked read fails; nothing was recorded
     */
    Result run() {
        LocalTransaction transaction = settings.transactions().begin();
        ReentrancyGuard.enter();
        PendingWrite write;
        try {
            Optional<SagaInstance> locked = lockOrRollBack(transaction);
            SagaInstance instance = locked.orElse(null);
            if (instance == null || instance.status() != SagaStatus.COMPENSATING
                    || EngineSagaLifecycle.remainingCompensations(instance).isEmpty()) {
                transaction.rollback();
                return new Stopped(instance == null ? null : instance.status());
            }
            step = EngineSagaLifecycle.remainingCompensations(instance).get(0);
            lifecycle.load(instance);
            try {
                runCompensation(instance);
                write = commit(transaction);
            } catch (SagaConcurrencyConflictException conflict) {
                if (writing) {
                    discard(transaction, conflict);
                    with.diagnostics().unitOfWorkConflict(correlation);
                    return new Conflicted(unitOfWorkId);
                }
                return failed(transaction, conflict);
            } catch (RuntimeException | Error failure) {
                return failed(transaction, failure);
            }
        } finally {
            ReentrancyGuard.exit();
        }
        with.diagnostics().compensationStep(correlation, step,
                write.instance().steps().get(step).compensation().status());
        if (write.instance().status() == SagaStatus.COMPENSATED) {
            with.diagnostics().compensationEnded(correlation);
        }
        return new Committed(step, write.instance(), unitOfWorkId, this::storeAfterCommit);
    }

    /** The id of this unit of work. */
    String unitOfWorkId() {
        return unitOfWorkId;
    }

    @Override
    public void record(Object domainEvent) {
        if (!domainEvent.getClass().isAnnotationPresent(DomainEvent.class)) {
            IllegalArgumentException invalid = new IllegalArgumentException(
                    domainEvent.getClass().getName() + " is not annotated @DomainEvent");
            failed(invalid);
            throw invalid;
        }
        recorded.add(domainEvent);
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
    public void recordOutboxCommand(OutgoingCommand command) {
        outboxCommands.add(command);
    }

    private Optional<SagaInstance> lockOrRollBack(LocalTransaction transaction) {
        try {
            return settings.storage().findByIdAndLock(sagaId);
        } catch (RuntimeException | Error lockFailure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, lockFailure);
            throw lockFailure;
        }
    }

    private void runCompensation(SagaInstance instance) {
        Compensation compensation = saga.compensationFor(step).orElseThrow(() -> new IllegalStateException(
                "Step " + step + " of saga instance " + sagaId + " is NOT_ATTEMPTED but " + saga.typeName()
                        + " declares no @SagaCompensation for it"));
        Object sagaObject = newSagaObject();
        EngineSagaContext context = new EngineSagaContext(sagaId, settings.contextStore(),
                settings.sources().clock());
        saga.inject(sagaObject, new Injection(sagaId, context, with.gateway()));
        commandIds.startStep(compensation.name());
        DomainEventScope.runWith(this, () -> saga.run(compensation, sagaObject, instance.compensationTrigger()));
        failIfReported();
        lifecycle.recordCompensated(sagaId, step);
    }

    private PendingWrite commit(LocalTransaction transaction) {
        failIfReported();
        if (transaction.isRollbackOnly()) {
            throw new IllegalStateException("Compensation unit of work " + unitOfWorkId + " of saga instance "
                    + sagaId + " was marked rollback-only");
        }
        PendingWrite write = lifecycle.drain(sagaId);
        writing = true;
        settings.storage().save(write.instance(), write.entries());
        writing = false;
        if (settings.outboxTiming() == Outbox.Timing.IN_TRANSACTION) {
            buffered().ifPresent(settings.outbox()::store);
        }
        writing = true;
        transaction.commit();
        return write;
    }

    private Result failed(LocalTransaction transaction, Throwable thrown) {
        Throwable cause = firstFailure != null ? firstFailure : thrown;
        discard(transaction, cause);
        with.diagnostics().unitOfWorkRolledBack(correlation, step, cause);
        return new Failed(step, cause, correlation);
    }

    private void discard(LocalTransaction transaction, Throwable cause) {
        recorded.clear();
        outboxCommands.clear();
        LocalTransactions.rollBackAfter(settings.transactions(), transaction, cause);
    }

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

    private Optional<OutgoingMessages> buffered() {
        return recorded.isEmpty() && outboxCommands.isEmpty() ? Optional.empty()
                : Optional.of(new OutgoingMessages(unitOfWorkId, saga.typeName(), sagaId, List.copyOf(recorded),
                        outboxCommands));
    }

    private Object newSagaObject() {
        Class<?> type = saga.type();
        Object sagaObject = settings.sagaObjects().map(provider -> provider.newInstance(type))
                .orElseGet(saga::newInstance);
        if (!type.isInstance(sagaObject)) {
            throw new IllegalStateException("SagaObjectProvider returned " + sagaObject + " for @Saga class "
                    + type.getName());
        }
        return sagaObject;
    }

    private void failIfReported() {
        if (firstFailure != null) {
            throw UnitOfWork.propagate(firstFailure);
        }
    }
}
