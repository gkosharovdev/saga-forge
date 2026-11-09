package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.InstanceLocks.Lease;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.AttemptResult;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Collaborators;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Committed;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Failed;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Finished;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Resume;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Trigger;
import java.time.Duration;
import java.util.List;

/**
 * Runs the attempts of one event for one saga instance (005 contracts/attempt-loop.md).
 *
 * <p>Holds the instance's in-process lock for the whole sequence, so one unit of work per instance still holds
 * (004 FR-025). Each attempt is a new {@link SagaUnitOfWork} re-running the triggering event from the start. After a
 * failed attempt the failure is classified with the failing step's {@code @RetryPolicy} ({@link FailureClassifier}):
 * a {@code TRANSIENT} failure with attempts left claims the next attempt ({@link AttemptWrite}), waits the policy's
 * backoff and runs it; any other outcome is written {@code FAILED} ({@link FailedWrite}), a {@code TRANSIENT} failure
 * on the last attempt as {@code EXHAUSTED}. A redelivery that finds a retry claimed by an earlier call claims the next
 * attempt before running it, or writes {@code FAILED} {@code EXHAUSTED} if none is left. An interrupted wait returns
 * {@code NOT_HANDLED} with the claimed attempt left recorded.</p>
 *
 * <p>The lock is released before the {@code AFTER_COMMIT} handoff of a committed attempt, and after the
 * {@code FAILED} write otherwise.</p>
 */
final class AttemptLoop {

    private final Collaborators with;
    private final EngineSettings settings;

    AttemptLoop(Collaborators with) {
        this.with = with;
        this.settings = with.settings();
    }

    /**
     * Runs the attempts for {@code trigger}, holding {@code instanceLease} until they end.
     *
     * @throws RuntimeException if a locked read, an attempt write or the {@code FAILED} write fails; nothing more was
     *                          recorded
     */
    SagaHandlingResult run(Trigger trigger, Lease instanceLease) {
        try (instanceLease) {
            int attempt = 1;
            boolean firstOfCall = true;
            while (true) {
                AttemptResult result = new SagaUnitOfWork(trigger, with, attempt, firstOfCall).run();
                firstOfCall = false;
                switch (result) {
                    case Finished finished -> {
                        return finished.result();
                    }
                    case Committed committed -> {
                        instanceLease.close();
                        return committed.handOff();
                    }
                    case Resume resume -> {
                        Integer next = resume(trigger, resume);
                        if (next == null) {
                            return exhausted(trigger, resume);
                        }
                        attempt = next;
                    }
                    case Failed failed -> {
                        RetryPolicy policy = trigger.saga().retryPolicyOf(failed.step());
                        FailureClassification classification = FailureClassifier.classify(failed.cause(), policy);
                        if (classification != FailureClassification.TRANSIENT
                                || failed.attempt() >= policy.maxAttempts()) {
                            return failedWrite(trigger, failed, classification);
                        }
                        SagaFailure transientFailure = SagaFailure.of(failed.step(), failed.cause(),
                                settings.sources().now(), FailureClassification.TRANSIENT, failed.attempt());
                        int next = failed.attempt() + 1;
                        PendingRetry claim = new PendingRetry(trigger.step().name(), next, transientFailure);
                        if (!new AttemptWrite(trigger.saga(), settings, with.diagnostics())
                                .attemptFailed(failed.correlation(), claim, failed.cause())) {
                            return failedWrite(trigger, failed, classification);
                        }
                        long wait = Backoff.waitBefore(policy, next);
                        with.diagnostics().unitOfWorkRetrying(failed.correlation(), transientFailure, next, wait);
                        if (!pause(wait)) {
                            with.diagnostics().retryInterrupted(failed.correlation(), next);
                            return notHandled(trigger, failed.correlation());
                        }
                        attempt = next;
                    }
                }
            }
        }
    }

    /**
     * Claims the attempt after the one an earlier call claimed; {@code null} if the failing step's policy allows no
     * further attempt.
     */
    private Integer resume(Trigger trigger, Resume resume) {
        PendingRetry claimed = resume.retry();
        int next = claimed.attemptsStarted() + 1;
        if (next > trigger.saga().retryPolicyOf(claimed.lastFailure().stepName()).maxAttempts()) {
            return null;
        }
        PendingRetry claim = new PendingRetry(claimed.triggerStep(), next, claimed.lastFailure());
        if (!new AttemptWrite(trigger.saga(), settings, with.diagnostics()).claim(resume.correlation(), claim)) {
            return null;
        }
        return next;
    }

    private SagaHandlingResult exhausted(Trigger trigger, Resume resume) {
        PendingRetry claimed = resume.retry();
        SagaFailure failure = claimed.lastFailure().classifiedAs(FailureClassification.EXHAUSTED,
                claimed.attemptsStarted());
        return new FailedWrite(trigger.saga(), settings, with.diagnostics())
                .record(resume.correlation(), failure, null, trigger.started());
    }

    private SagaHandlingResult failedWrite(Trigger trigger, Failed failed, FailureClassification classification) {
        FailureClassification recorded = classification == FailureClassification.TRANSIENT
                ? FailureClassification.EXHAUSTED
                : classification;
        SagaFailure failure = SagaFailure.of(failed.step(), failed.cause(), settings.sources().now(), recorded,
                failed.attempt());
        return new FailedWrite(trigger.saga(), settings, with.diagnostics())
                .record(failed.correlation(), failure, failed.cause(), trigger.started());
    }

    /** Waits {@code millis}; {@code false} if interrupted, with the interrupt flag restored. */
    private boolean pause(long millis) {
        try {
            settings.retry().pause().pause(Duration.ofMillis(millis));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static SagaHandlingResult notHandled(Trigger trigger, Correlation about) {
        return new SagaHandlingResult(trigger.saga().typeName(), trigger.sagaId(), HandlingOutcome.NOT_HANDLED,
                trigger.started(), null, null, about.unitOfWorkId(), List.of());
    }
}
