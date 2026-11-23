package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.opentelemetry.api.common.Attributes;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.InstanceLocks.Lease;
import io.github.gkosharovdev.sagaforge.runtime.internal.CompensationRun.Summary;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.AttemptResult;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.Committed;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.Compensating;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.Failed;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.Finished;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.Resume;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunSteps.Trigger;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the attempts of one event for one saga instance (005 contracts/attempt-loop.md).
 *
 * <p>Holds the instance's in-process lock for the whole sequence, so one unit of work per instance still holds
 * (004 FR-025). Each attempt is a new unit of work running {@link RunSteps}, re-running the triggering event from the
 * start. After a failed attempt the failure is classified with the failing step's {@code @RetryPolicy}
 * ({@link FailureClassifier}): a {@code TRANSIENT} failure with attempts left claims the next attempt
 * ({@link ClaimAttempt}), waits the policy's backoff and runs it; any other outcome is written {@code FAILED}
 * ({@link RecordFailure}), a {@code TRANSIENT} failure
 * on the last attempt as {@code EXHAUSTED}. A redelivery that finds a retry claimed by an earlier call claims the next
 * attempt before running it, or writes {@code FAILED} {@code EXHAUSTED} if none is left. An interrupted wait returns
 * {@code NOT_HANDLED} with the claimed attempt left recorded.</p>
 *
 * <p>The lock is released before the {@code AFTER_COMMIT} outbox of a committed attempt and of the {@code FAILED}
 * write (011: it carries the {@code SagaFailed} outcome event, and its outbox failures are reported in the
 * {@code FAILED} result).</p>
 *
 * <p>Since 006 a committed attempt that started compensation, an attempt that found the instance already
 * {@code COMPENSATING}, and a {@code FINAL} or {@code EXHAUSTED} failure after a step committed
 * ({@link StartCompensation} instead of {@code RecordFailure}) continue with {@link CompensationRun} under the same
 * lock; the lock is released after the
 * run's last write and every committed unit of work's {@code AFTER_COMMIT} outbox then runs in commit order
 * (specs/006-saga-compensation contracts/compensation-run.md).</p>
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
                AttemptResult result = tracedAttempt(trigger, attempt, firstOfCall);
                firstOfCall = false;
                switch (result) {
                    case Finished finished -> {
                        return finished.result();
                    }
                    case Committed committed -> {
                        if (committed.compensationStarted()) {
                            return compensate(trigger, instanceLease, compensationCorrelation(trigger),
                                    () -> committed.store().outboxFailures(), committed.unitOfWorkId());
                        }
                        instanceLease.close();
                        return committed.store();
                    }
                    case Compensating compensating -> {
                        with.diagnostics().compensationResumed(compensating.correlation(), trigger.step().name());
                        return compensate(trigger, instanceLease, compensating.correlation(), null, null);
                    }
                    case Resume resume -> {
                        Integer next = resume(trigger, resume);
                        if (next == null) {
                            return exhausted(trigger, resume, instanceLease);
                        }
                        attempt = next;
                    }
                    case Failed failed -> {
                        RetryPolicy policy = trigger.saga().retryPolicyOf(failed.step());
                        FailureClassification classification = FailureClassifier.classify(failed.cause(), policy);
                        if (classification != FailureClassification.TRANSIENT
                                || failed.attempt() >= policy.maxAttempts()) {
                            return failedWrite(trigger, failed, classification, instanceLease);
                        }
                        SagaFailure transientFailure = SagaFailure.of(failed.step(), failed.cause(),
                                settings.sources().now(), FailureClassification.TRANSIENT, failed.attempt());
                        int next = failed.attempt() + 1;
                        PendingRetry claim = new PendingRetry(trigger.step().name(), next, transientFailure);
                        if (!ClaimAttempt.afterFailure(with, trigger.saga(), failed.correlation(), claim,
                                failed.cause())) {
                            return failedWrite(trigger, failed, classification, instanceLease);
                        }
                        long wait = Backoff.waitBefore(policy, next);
                        with.diagnostics().unitOfWorkRetrying(failed.correlation(), transientFailure, next, wait);
                        retryScheduled(trigger, failed.step(), next, wait);
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
        if (!ClaimAttempt.onResume(with, trigger.saga(), resume.correlation(), claim)) {
            return null;
        }
        return next;
    }

    private SagaHandlingResult exhausted(Trigger trigger, Resume resume, Lease instanceLease) {
        PendingRetry claimed = resume.retry();
        retryExhausted(trigger, claimed.lastFailure().stepName());
        SagaFailure failure = claimed.lastFailure().classifiedAs(FailureClassification.EXHAUSTED,
                claimed.attemptsStarted());
        return finalOrFailed(trigger, resume.correlation(), failure, null, instanceLease);
    }

    private SagaHandlingResult failedWrite(Trigger trigger, Failed failed, FailureClassification classification,
            Lease instanceLease) {
        FailureClassification recorded = classification == FailureClassification.TRANSIENT
                ? FailureClassification.EXHAUSTED
                : classification;
        if (recorded == FailureClassification.EXHAUSTED) {
            retryExhausted(trigger, failed.step());
        }
        SagaFailure failure = SagaFailure.of(failed.step(), failed.cause(), settings.sources().now(), recorded,
                failed.attempt());
        return finalOrFailed(trigger, failed.correlation(), failure, failed.cause(), instanceLease);
    }

    /**
     * A {@code FINAL} or {@code EXHAUSTED} failure starts compensation if a step committed, then runs it (006 FR-003,
     * FR-006); an {@code UNCLASSIFIED} one, or one with nothing committed, is written {@code FAILED} (005).
     */
    private SagaHandlingResult finalOrFailed(Trigger trigger, Correlation about, SagaFailure failure,
            Throwable cause, Lease instanceLease) {
        boolean compensable = failure.classification() == FailureClassification.FINAL
                || failure.classification() == FailureClassification.EXHAUSTED;
        if (!compensable) {
            return RecordFailure.record(with, trigger.saga(), about, failure, cause, trigger.started())
                    .report(instanceLease);
        }
        List<Supplier<List<Throwable>>> startOutbox = new ArrayList<>();
        java.util.Optional<RecordFailure.Written> notStarted = StartCompensation.record(with, trigger.saga(), about,
                failure, cause, trigger.started(), startOutbox);
        if (notStarted.isPresent()) {
            return notStarted.get().report(instanceLease);
        }
        Supplier<List<Throwable>> triggerOutbox = () -> startOutbox.stream()
                .flatMap(outbox -> outbox.get().stream()).toList();
        return compensate(trigger, instanceLease, about.inUnitOfWork(null), triggerOutbox, about.unitOfWorkId());
    }

    /**
     * Runs the remaining compensations under {@code instanceLease}, releases it, then runs {@code triggerOutbox} (the
     * {@code AFTER_COMMIT} outbox of the unit of work that started compensation, if any) and each committed
     * compensation's outbox, in commit order, also when the run raised.
     */
    private SagaHandlingResult compensate(Trigger trigger, Lease instanceLease, Correlation about,
            Supplier<List<Throwable>> triggerOutbox, String triggerUnitOfWorkId) {
        List<Supplier<List<Throwable>>> outboxs = new ArrayList<>();
        if (triggerOutbox != null) {
            outboxs.add(triggerOutbox);
        }
        Summary summary;
        List<Throwable> outboxFailures = new ArrayList<>();
        with.instrumentation().event("saga_forge.compensation.started", Attributes.empty());
        try {
            summary = new CompensationRun(with).run(trigger.saga(), trigger.sagaId(), about, outboxs,
                    triggerUnitOfWorkId);
        } finally {
            instanceLease.close();
            outboxs.forEach(outbox -> outboxFailures.addAll(outbox.get()));
        }
        boolean committed = summary.outcome() != HandlingOutcome.NOT_HANDLED;
        return new SagaHandlingResult(trigger.saga().typeName(), trigger.sagaId(), summary.outcome(),
                trigger.started(), null, null, summary.lastUnitOfWorkId(),
                committed ? outboxFailures : List.of());
    }

    private static Correlation compensationCorrelation(Trigger trigger) {
        return Correlation.of(trigger.event()).forSaga(trigger.saga().typeName()).instance(trigger.sagaId());
    }

    /** One attempt inside a {@code saga_forge.attempt} span (010 research §2). */
    private AttemptResult tracedAttempt(Trigger trigger, int attempt, boolean firstOfCall) {
        try (SagaInstrumentation.Traced span = with.instrumentation().start(SagaInstrumentation.ATTEMPT)) {
            span.set(SagaInstrumentation.SAGA_TYPE, trigger.saga().typeName())
                    .set(SagaInstrumentation.SAGA_ID, trigger.sagaId())
                    .set(SagaInstrumentation.ATTEMPT_NUMBER, (long) attempt);
            AttemptResult result;
            try {
                result = RunSteps.attempt(with, trigger, attempt, firstOfCall);
            } catch (RuntimeException | Error failure) {
                span.fail(failure);
                throw failure;
            }
            switch (result) {
                case Committed committed -> span.set(SagaInstrumentation.ATTEMPT_RESULT, "committed")
                        .set(SagaInstrumentation.UNIT_OF_WORK_ID, committed.unitOfWorkId());
                case Failed failed -> span.set(SagaInstrumentation.ATTEMPT_RESULT, "rolled_back")
                        .set(SagaInstrumentation.STEP_NAME, failed.step())
                        .set(SagaInstrumentation.FAILURE_CLASSIFICATION, FailureClassifier.classify(failed.cause(),
                                trigger.saga().retryPolicyOf(failed.step())).name())
                        .fail(failed.cause());
                default -> span.set(SagaInstrumentation.ATTEMPT_RESULT, "not_run");
            }
            return result;
        }
    }

    private void retryScheduled(Trigger trigger, String step, int nextAttempt, long waitMillis) {
        SagaInstrumentation telemetry = with.instrumentation();
        telemetry.event("saga_forge.retry.scheduled", Attributes.of(SagaInstrumentation.ATTEMPT_NUMBER,
                (long) nextAttempt, SagaInstrumentation.RETRY_WAIT, waitMillis));
        telemetry.count(SagaInstrumentation.RETRY_SCHEDULED_COUNTER, Attributes.of(SagaInstrumentation.SAGA_TYPE,
                trigger.saga().typeName(), SagaInstrumentation.STEP_NAME, step));
    }

    private void retryExhausted(Trigger trigger, String step) {
        with.instrumentation().count(SagaInstrumentation.RETRY_EXHAUSTED_COUNTER, Attributes.of(
                SagaInstrumentation.SAGA_TYPE, trigger.saga().typeName(), SagaInstrumentation.STEP_NAME, step));
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
