package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import java.util.Optional;

/**
 * Claims the next attempt of a unit of work in its own transaction, before the engine waits for or runs it (005
 * research §9, contracts/attempt-loop.md): locks the instance, sets its {@code pendingRetry} and, for a failed
 * attempt, appends an {@code ATTEMPT_FAILED} entry. The sibling of {@link FailedWrite}.
 */
final class AttemptWrite {

    private final SagaModel saga;
    private final EngineSettings settings;
    private final Diagnostics diagnostics;

    AttemptWrite(SagaModel saga, EngineSettings settings, Diagnostics diagnostics) {
        this.saga = saga;
        this.settings = settings;
        this.diagnostics = diagnostics;
    }

    /**
     * Records that an attempt failed transiently and claims {@code retry.attemptsStarted()}.
     *
     * @param about the correlation of the failed attempt
     * @param retry the claim, carrying the failed attempt as {@code lastFailure}
     * @param cause the failure of that attempt; added as suppressed to a failure of this write
     * @return {@code false} if the instance is gone or terminal, so nothing was claimed
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    boolean attemptFailed(Correlation about, PendingRetry retry, Throwable cause) {
        return write(about, retry, cause, true);
    }

    /**
     * Claims {@code retry.attemptsStarted()} for a redelivery resuming a retry; adds no entry.
     *
     * @return {@code false} if the instance is gone or terminal, so nothing was claimed
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    boolean claim(Correlation about, PendingRetry retry) {
        return write(about, retry, null, false);
    }

    private boolean write(Correlation about, PendingRetry retry, Throwable cause, boolean failed) {
        LocalTransaction transaction = settings.transactions().begin();
        try {
            Optional<SagaInstance> stored = settings.storage().findByIdAndLock(about.sagaId());
            if (stored.isEmpty() || EngineSagaLifecycle.isTerminal(stored.get().status())) {
                transaction.rollback();
                return false;
            }
            EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(saga, settings.storage(), settings.sources());
            lifecycle.load(stored.get());
            if (failed) {
                lifecycle.attemptFailed(about.sagaId(), retry);
            } else {
                lifecycle.claimAttempt(about.sagaId(), retry);
            }
            PendingWrite write = lifecycle.drain(about.sagaId());
            settings.storage().save(write.instance(), write.entries());
            transaction.commit();
            return true;
        } catch (RuntimeException | Error writeFailure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, writeFailure);
            if (cause != null && writeFailure != cause) {
                writeFailure.addSuppressed(cause);
            }
            diagnostics.instanceFailedNotRecorded(about, writeFailure);
            throw writeFailure;
        }
    }
}
