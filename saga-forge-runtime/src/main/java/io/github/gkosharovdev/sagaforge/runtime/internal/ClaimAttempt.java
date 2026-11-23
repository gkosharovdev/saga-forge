package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import java.util.Optional;

/**
 * Claims the next attempt of an event's steps in a unit of work of its own, before the engine waits for or runs it
 * (005 research §9, contracts/attempt-loop.md): sets the instance's {@code pendingRetry} and, for a failed attempt,
 * appends an {@code ATTEMPT_FAILED} entry. A missing or terminal instance claims nothing.
 */
final class ClaimAttempt implements UnitOfWork.Work<Void, String> {

    private final PendingRetry retry;
    private final boolean afterFailure;

    private ClaimAttempt(PendingRetry retry, boolean afterFailure) {
        this.retry = retry;
        this.afterFailure = afterFailure;
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
    static boolean afterFailure(Collaborators with, SagaModel saga, Correlation about, PendingRetry retry,
            Throwable cause) {
        return claim(with, saga, about, new ClaimAttempt(retry, true), cause);
    }

    /**
     * Claims {@code retry.attemptsStarted()} for a redelivery resuming a retry; adds no entry.
     *
     * @return {@code false} if the instance is gone or terminal, so nothing was claimed
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    static boolean onResume(Collaborators with, SagaModel saga, Correlation about, PendingRetry retry) {
        return claim(with, saga, about, new ClaimAttempt(retry, false), null);
    }

    private static boolean claim(Collaborators with, SagaModel saga, Correlation about, ClaimAttempt work,
            Throwable cause) {
        try {
            return switch (UnitOfWork.forInstance(with, saga, about.sagaId(), about).run(work)) {
                case UnitOfWork.Skipped<Void, String> skipped -> false;
                case UnitOfWork.Committed<Void, String> committed -> {
                    committed.release();
                    yield true;
                }
                case UnitOfWork.Failed<Void, String> failed -> throw UnitOfWork.propagate(failed.cause());
                case UnitOfWork.Conflicted<Void, String> conflicted -> throw conflicted.conflict();
            };
        } catch (RuntimeException | Error writeFailure) {
            if (cause != null && writeFailure != cause) {
                writeFailure.addSuppressed(cause);
            }
            with.diagnostics().instanceFailedNotRecorded(about, writeFailure);
            throw writeFailure;
        }
    }

    @Override
    public Admission<String> admit(Optional<SagaInstance> locked) {
        return InstanceChecks.missingOrTerminal(locked) ? Admission.skip("missing or terminal") : Admission.proceed();
    }

    @Override
    public Void perform(WorkContext context) {
        if (afterFailure) {
            context.lifecycle().attemptFailed(context.sagaId(), retry);
        } else {
            context.lifecycle().claimAttempt(context.sagaId(), retry);
        }
        return null;
    }
}
