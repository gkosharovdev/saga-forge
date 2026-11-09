package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandoff;
import io.github.gkosharovdev.sagaforge.core.spi.HandedOffEvents;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import java.util.ArrayList;
import java.util.List;

/**
 * The unit of work around a {@code CommandGateway.send} made outside any engine unit of work (FR-016, 002
 * contracts/command-gateway.md "Outside a unit of work", contracts/contract-deltas.md).
 *
 * <p>{@link #run(Runnable)}: begins one {@link LocalTransaction}, binds itself as the thread's recorder for the
 * handler call, then commits. After the commit it hands every recorded event, in recording order, to the
 * {@link EventHandoff} as one {@code HandedOffEvents(unitOfWorkId, null, null, events)}, if there is any.</p>
 *
 * <p>On a failure (the handler's own exception, a failure {@link #failed(Throwable) reported} by a nested
 * {@code send} even if the handler caught it, or a transaction found rollback-only at commit time) it rolls back,
 * discards the recorded events and rethrows the first reported failure, else the thrown one, unchanged.</p>
 *
 * <p>Transitional (US1): the handoff is always called after commit, whatever its timing, and a failing handoff
 * propagates from {@code send}; {@code IN_TRANSACTION} timing and handoff-failure handling come with T085.</p>
 *
 * <p>Not thread-safe: confined to the thread that called {@code send}.</p>
 */
final class NonSagaUnitOfWork implements UnitOfWork {

    private final TransactionBoundary transactions;
    private final EventHandoff handoff;
    private final String unitOfWorkId;
    private final List<Object> recorded = new ArrayList<>();
    private Throwable firstFailure;

    /**
     * @param transactions begins this unit of work's transaction
     * @param handoff      receives the recorded events after commit
     * @param unitOfWorkId fresh id of this unit of work
     */
    NonSagaUnitOfWork(TransactionBoundary transactions, EventHandoff handoff, String unitOfWorkId) {
        this.transactions = transactions;
        this.handoff = handoff;
        this.unitOfWorkId = unitOfWorkId;
    }

    /** Runs {@code handlerCall} in this unit of work, then commits and hands the recorded events off. */
    void run(Runnable handlerCall) {
        LocalTransaction transaction = transactions.begin();
        try {
            DomainEventScope.runWith(this, handlerCall);
            commit(transaction);
        } catch (RuntimeException | Error failure) {
            Throwable reported = firstFailure != null ? firstFailure : failure;
            recorded.clear();
            LocalTransactions.rollBackAfter(transactions, transaction, reported);
            throw UnitOfWork.propagate(reported);
        }
        handOffRecorded();
    }

    @Override
    public void record(Object domainEvent) {
        if (domainEvent == null || !domainEvent.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException("Not a @DomainEvent: " + domainEvent);
        }
        recorded.add(domainEvent);
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
        transaction.commit();
    }

    private void handOffRecorded() {
        if (!recorded.isEmpty()) {
            handoff.handOff(new HandedOffEvents(unitOfWorkId, null, null, recorded));
        }
    }
}
