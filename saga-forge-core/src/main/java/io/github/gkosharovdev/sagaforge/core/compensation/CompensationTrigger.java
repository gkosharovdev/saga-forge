package io.github.gkosharovdev.sagaforge.core.compensation;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import java.time.Instant;

/**
 * Why a saga instance entered compensation; recorded once and never changed ({@code specs/006-saga-compensation}
 * FR-004, FR-010, FR-017).
 *
 * <p>A {@code @SagaCompensation} method that declares a parameter of this type receives the persisted trigger, so its
 * business rules can depend on the cause (for example, refund in full on a rejected delivery). The triggering event
 * itself is not available: data a compensation needs from it travels through {@code SagaContext}.</p>
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a rule above is violated.</p>
 *
 * @param kind      what started compensation; non-null
 * @param stepName  the {@code @StartCompensation} step ({@code FAILURE_OUTCOME}) or the step that failed; non-blank
 * @param eventType fully qualified class name of the event the {@code @StartCompensation} step handled; non-null if
 *                  and only if {@code kind == FAILURE_OUTCOME}
 * @param failure   the recorded step failure, classified {@code FINAL} for {@code FINAL_FAILURE} and
 *                  {@code EXHAUSTED} for {@code RETRIES_EXHAUSTED}; non-null if and only if
 *                  {@code kind != FAILURE_OUTCOME}
 * @param at        when compensation started; non-null
 */
public record CompensationTrigger(CompensationTriggerKind kind, String stepName, String eventType,
        SagaFailure failure, Instant at) {

    public CompensationTrigger {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }
        if (at == null) {
            throw new IllegalArgumentException("at must not be null");
        }
        boolean outcome = kind == CompensationTriggerKind.FAILURE_OUTCOME;
        if (outcome != (eventType != null)) {
            throw new IllegalArgumentException(
                    "eventType must be non-null if and only if kind is FAILURE_OUTCOME, but kind is " + kind);
        }
        if (eventType != null && eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (outcome == (failure != null)) {
            throw new IllegalArgumentException(
                    "failure must be non-null if and only if kind is not FAILURE_OUTCOME, but kind is " + kind);
        }
        if (failure != null) {
            FailureClassification expected = kind == CompensationTriggerKind.FINAL_FAILURE
                    ? FailureClassification.FINAL
                    : FailureClassification.EXHAUSTED;
            if (failure.classification() != expected) {
                throw new IllegalArgumentException("failure of a " + kind + " trigger must be classified " + expected
                        + ", but is " + failure.classification());
            }
            if (!failure.stepName().equals(stepName)) {
                throw new IllegalArgumentException("stepName " + stepName + " must be the failed step "
                        + failure.stepName());
            }
        }
    }

    /**
     * @param stepName  the {@code @StartCompensation} step whose unit of work committed
     * @param eventType fully qualified class name of the event it handled
     * @param at        when compensation started
     * @return a {@code FAILURE_OUTCOME} trigger
     */
    public static CompensationTrigger failureOutcome(String stepName, String eventType, Instant at) {
        return new CompensationTrigger(CompensationTriggerKind.FAILURE_OUTCOME, stepName, eventType, null, at);
    }

    /**
     * @param failure the step failure, classified {@code FINAL} or {@code EXHAUSTED}
     * @param at      when compensation started
     * @return a {@code FINAL_FAILURE} or {@code RETRIES_EXHAUSTED} trigger
     * @throws IllegalArgumentException if {@code failure} is {@code null} or classified {@code TRANSIENT} or
     *                                  {@code UNCLASSIFIED}, which never start compensation (FR-006)
     */
    public static CompensationTrigger failed(SagaFailure failure, Instant at) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        CompensationTriggerKind kind = switch (failure.classification()) {
            case FINAL -> CompensationTriggerKind.FINAL_FAILURE;
            case EXHAUSTED -> CompensationTriggerKind.RETRIES_EXHAUSTED;
            case TRANSIENT, UNCLASSIFIED -> throw new IllegalArgumentException(
                    "a " + failure.classification() + " failure does not start compensation");
        };
        return new CompensationTrigger(kind, failure.stepName(), null, failure, at);
    }
}
