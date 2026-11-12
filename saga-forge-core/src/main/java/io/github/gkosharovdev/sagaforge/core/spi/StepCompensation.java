package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import java.time.Instant;

/**
 * Compensation state of one completed step ({@code specs/006-saga-compensation} FR-017).
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a rule above is violated.</p>
 *
 * @param status  non-null
 * @param at      when the status was reached; {@code null} if and only if {@code status == NOT_ATTEMPTED}
 * @param failure why the compensation failed, classified {@code FINAL}, {@code UNCLASSIFIED} or, for a transient
 *                cause that was not retried, {@code EXHAUSTED} after its single attempt; non-null if and only if
 *                {@code status == FAILED}
 */
public record StepCompensation(CompensationStatus status, Instant at, SagaFailure failure) {

    private static final StepCompensation NOT_ATTEMPTED = new StepCompensation(CompensationStatus.NOT_ATTEMPTED,
            null, null);

    public StepCompensation {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        boolean notAttempted = status == CompensationStatus.NOT_ATTEMPTED;
        if (notAttempted != (at == null)) {
            throw new IllegalArgumentException(
                    "at must be null if and only if status is NOT_ATTEMPTED, but status is " + status);
        }
        boolean failed = status == CompensationStatus.FAILED;
        if (failed != (failure != null)) {
            throw new IllegalArgumentException(
                    "failure must be non-null if and only if status is FAILED, but status is " + status);
        }
        if (failure != null && failure.classification() == FailureClassification.TRANSIENT) {
            throw new IllegalArgumentException("a compensation failure must be classified FINAL, EXHAUSTED or "
                    + "UNCLASSIFIED, but is TRANSIENT");
        }
    }

    /** @return the status of a compensation that has not run yet */
    public static StepCompensation notAttempted() {
        return NOT_ATTEMPTED;
    }

    /** @return a step without a declared compensation, recorded at {@code at} */
    public static StepCompensation nothingToCompensate(Instant at) {
        return new StepCompensation(CompensationStatus.NOTHING_TO_COMPENSATE, at, null);
    }

    /** @return a compensation that committed at {@code at} */
    public static StepCompensation compensated(Instant at) {
        return new StepCompensation(CompensationStatus.COMPENSATED, at, null);
    }

    /**
     * @param failure why the compensation failed; its {@code failedAt} becomes {@code at}
     * @return a failed compensation
     * @throws IllegalArgumentException if {@code failure} is {@code null}
     */
    public static StepCompensation failed(SagaFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        return new StepCompensation(CompensationStatus.FAILED, failure.failedAt(), failure);
    }
}
