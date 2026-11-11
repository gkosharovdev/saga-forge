package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The unit of work around a {@code CommandGateway.send} made outside any engine unit of work (FR-016, 002
 * contracts/command-gateway.md "Outside a unit of work", contracts/contract-deltas.md).
 *
 * <p>{@link #run(Runnable)}: begins one {@link LocalTransaction}, binds itself as the thread's recorder for the
 * handler call, then commits. It hands every recorded event, in recording order, to the {@link Outbox} as one
 * {@code OutgoingMessages(unitOfWorkId, null, null, events)}, if there is any, at the outbox's timing.</p>
 *
 * <p>On a failure (the handler's own exception, a failure {@link #failed(Throwable) reported} by a nested
 * {@code send} even if the handler caught it, or a transaction found rollback-only at commit time) it rolls back,
 * discards the recorded events and rethrows the first reported failure, else the thrown one, unchanged.</p>
 *
 * <h2>Outbox and reentrancy (contracts/event-handoff.md "Failure handling", FR-007)</h2>
 * <p>The thread is marked "unit of work running" ({@link ReentrancyGuard}) from {@code begin()} until the commit or
 * rollback has finished. With an {@code IN_TRANSACTION} outbox the non-empty batch goes to the outbox before
 * {@code commit()}, inside the transaction; its failure rolls back and is rethrown from {@code send}. With
 * {@code AFTER_COMMIT} it goes to the outbox after the commit with the marker cleared; its failure is logged
 * ({@code saga.outbox.failed}, without saga type or id) and not thrown, since the handler's change is committed.</p>
 *
 * <p>Not thread-safe: confined to the thread that called {@code send}.</p>
 */
final class NonSagaUnitOfWork implements UnitOfWork {

    private final EngineSettings settings;
    private final Diagnostics diagnostics;
    private final String unitOfWorkId;
    private final List<Object> recorded = new ArrayList<>();
    private final List<OutgoingCommand> outboxCommands = new ArrayList<>();
    private Throwable firstFailure;

    /**
     * @param settings     the transactions and the outbox with its timing
     * @param diagnostics  where a failed {@code AFTER_COMMIT} outbox is logged
     * @param unitOfWorkId fresh id of this unit of work
     */
    NonSagaUnitOfWork(EngineSettings settings, Diagnostics diagnostics, String unitOfWorkId) {
        this.settings = settings;
        this.diagnostics = diagnostics;
        this.unitOfWorkId = unitOfWorkId;
    }

    /** Runs {@code handlerCall} in this unit of work, then commits and hands the recorded events off. */
    void run(Runnable handlerCall) {
        TransactionBoundary transactions = settings.transactions();
        LocalTransaction transaction = transactions.begin();
        ReentrancyGuard.enter();
        try {
            DomainEventScope.runWith(this, handlerCall);
            commit(transaction);
        } catch (RuntimeException | Error failure) {
            Throwable reported = firstFailure != null ? firstFailure : failure;
            recorded.clear();
            outboxCommands.clear();
            LocalTransactions.rollBackAfter(transactions, transaction, reported);
            throw UnitOfWork.propagate(reported);
        } finally {
            ReentrancyGuard.exit();
        }
        storeAfterCommit();
    }

    @Override
    public void record(Object domainEvent) {
        if (domainEvent == null || !domainEvent.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException("Not a @DomainEvent: " + domainEvent);
        }
        recorded.add(domainEvent);
    }

    @Override
    public void recordOutboxCommand(OutgoingCommand command) {
        outboxCommands.add(command);
    }

    @Override
    public void failed(Throwable failure) {
        if (firstFailure == null) {
            firstFailure = failure;
        }
    }

    private void commit(LocalTransaction transaction) {
        if (firstFailure != null) {
            throw UnitOfWork.propagate(firstFailure);
        }
        if (transaction.isRollbackOnly()) {
            throw new IllegalStateException("Unit of work " + unitOfWorkId + " was marked rollback-only");
        }
        if (settings.outboxTiming() == Outbox.Timing.IN_TRANSACTION) {
            batch().ifPresent(settings.outbox()::store);
        }
        transaction.commit();
    }

    private void storeAfterCommit() {
        if (settings.outboxTiming() != Outbox.Timing.AFTER_COMMIT) {
            OutboxCommitted.notify(settings, batch(), diagnostics, new Correlation(null, null, null, unitOfWorkId));
            return;
        }
        batch().ifPresent(batch -> {
            try {
                settings.outbox().store(batch);
            } catch (RuntimeException outboxFailure) {
                diagnostics.outboxFailed(new Correlation(null, null, null, unitOfWorkId), batch.events().size(),
                        outboxFailure);
            }
        });
    }

    private Optional<OutgoingMessages> batch() {
        return recorded.isEmpty() && outboxCommands.isEmpty() ? Optional.empty()
                : Optional.of(new OutgoingMessages(unitOfWorkId, null, null, recorded, outboxCommands));
    }
}
