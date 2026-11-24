package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Records a failed compensation in a unit of work of its own, after the compensation's unit of work rolled back
 * (specs/006-saga-compensation research §9, FR-016, SC-007): the step's compensation becomes {@code FAILED} and the
 * instance {@code COMPENSATION_FAILED}; steps not reached stay {@code NOT_ATTEMPTED}. Nothing is retried.
 *
 * <p>The failure is classified with 005's {@link FailureClassifier} and the default one-attempt policy:
 * {@code FINAL}, {@code UNCLASSIFIED}, or {@code EXHAUSTED} for a transient cause after its single attempt. If the
 * locked instance is no longer compensating, or the step no longer waits for its compensation (another process moved
 * on), nothing is written and {@link #record} returns {@code false}.</p>
 */
final class RecordCompensationFailure implements UnitOfWork.Work<SagaFailure, String> {

    private final String step;
    private final Throwable cause;

    private RecordCompensationFailure(String step, Throwable cause) {
        this.step = step;
        this.cause = cause;
    }

    /**
     * @param about the correlation of the rolled-back compensation unit of work
     * @param step  the step whose compensation failed
     * @param cause the failure that rolled it back; added as suppressed to a failure of this write
     * @param outboxs where a committed write adds its outbox step (011: it carries {@code SagaFailed}), which the
     *                caller runs after releasing the instance lock
     * @return {@code true} if the failed compensation was recorded
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    static boolean record(Collaborators with, SagaModel saga, Correlation about, String step, Throwable cause,
            List<Supplier<List<Throwable>>> outboxs) {
        Diagnostics diagnostics = with.diagnostics();
        try {
            return switch (UnitOfWork.forInstance(with, saga, about.sagaId(), about)
                    .run(new RecordCompensationFailure(step, cause))) {
                case UnitOfWork.Skipped<SagaFailure, String> skipped -> {
                    diagnostics.compensationNotRecorded(about, cause);
                    yield false;
                }
                case UnitOfWork.Committed<SagaFailure, String> committed -> {
                    diagnostics.compensationFailed(about, committed.value());
                    outboxs.add(committed::release);
                    yield true;
                }
                case UnitOfWork.Failed<SagaFailure, String> failed -> throw UnitOfWork.propagate(failed.cause());
                case UnitOfWork.Conflicted<SagaFailure, String> conflicted -> throw conflicted.conflict();
            };
        } catch (RuntimeException | Error writeFailure) {
            if (writeFailure != cause) {
                writeFailure.addSuppressed(cause);
            }
            diagnostics.compensationNotRecorded(about, writeFailure);
            throw writeFailure;
        }
    }

    @Override
    public Admission<String> admit(Optional<SagaInstance> locked) {
        return locked.filter(this::waitsForCompensation).isPresent()
                ? Admission.proceed()
                : Admission.skip("not waiting for the compensation of " + step);
    }

    @Override
    public SagaFailure perform(WorkContext context) {
        FailureClassification classification = FailureClassifier.classify(cause, SagaModel.defaultRetryPolicy());
        FailureClassification recorded = classification == FailureClassification.TRANSIENT
                ? FailureClassification.EXHAUSTED
                : classification;
        SagaFailure failure = SagaFailure.of(step, cause, context.settings().sources().now(), recorded, 1);
        context.lifecycle().recordCompensationFailed(context.sagaId(), step, failure);
        return failure;
    }

    private boolean waitsForCompensation(SagaInstance instance) {
        StepRecord record = instance.steps().get(step);
        return instance.status() == SagaStatus.COMPENSATING && record != null && record.compensation() != null
                && record.compensation().status() == CompensationStatus.NOT_ATTEMPTED;
    }
}
