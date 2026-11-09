package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * Ending a {@link LocalTransaction} on a failure path, where it may already have ended (a failed
 * {@code commit()} rolls back by contract).
 *
 * <p>Stateless and thread-safe.</p>
 */
final class LocalTransactions {

    private LocalTransactions() {
    }

    /** {@code true} while {@code transaction} is the boundary's active transaction on this thread. */
    static boolean isActive(TransactionBoundary boundary, LocalTransaction transaction) {
        return boundary.current().filter(current -> current == transaction).isPresent();
    }

    /**
     * Rolls {@code transaction} back after {@code failure} if it is still active. A rollback that fails itself is
     * added to {@code failure} as a suppressed exception, so the original failure is the one that propagates.
     */
    static void rollBackAfter(TransactionBoundary boundary, LocalTransaction transaction, Throwable failure) {
        if (!isActive(boundary, transaction)) {
            return;
        }
        try {
            transaction.rollback();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
