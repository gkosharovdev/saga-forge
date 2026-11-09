package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;

/**
 * Persisted description of the failure that moved a saga instance to {@link SagaStatus#FAILED}, or of a failed
 * attempt the engine retried.
 *
 * @param stepName       the step that failed; non-null
 * @param causeType      fully qualified class name of the failure; non-null
 * @param message        the failure's message; nullable
 * @param failedAt       when the failure was recorded; non-null
 * @param classification how the engine classified the failure; non-null ({@code specs/005-step-retry} FR-008)
 * @param attempts       the number of the attempt that failed, counting the first as 1; at least 1
 * @throws IllegalArgumentException if {@code stepName}, {@code causeType}, {@code failedAt} or
 *                                  {@code classification} is {@code null}, or {@code attempts < 1}
 */
public record SagaFailure(String stepName, String causeType, String message, Instant failedAt,
        FailureClassification classification, int attempts) {

    public SagaFailure {
        if (stepName == null) {
            throw new IllegalArgumentException("stepName must not be null");
        }
        if (causeType == null) {
            throw new IllegalArgumentException("causeType must not be null");
        }
        if (failedAt == null) {
            throw new IllegalArgumentException("failedAt must not be null");
        }
        if (classification == null) {
            throw new IllegalArgumentException("classification must not be null");
        }
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, but is " + attempts);
        }
    }

    /**
     * A failure of a single, unclassified attempt (the record as it was before {@code specs/005-step-retry}).
     *
     * @param stepName  the step that failed; non-null
     * @param causeType fully qualified class name of the failure; non-null
     * @param message   the failure's message; nullable
     * @param failedAt  when the failure was recorded; non-null
     * @throws IllegalArgumentException if {@code stepName}, {@code causeType}, or {@code failedAt} is {@code null}
     */
    public SagaFailure(String stepName, String causeType, String message, Instant failedAt) {
        this(stepName, causeType, message, failedAt, FailureClassification.UNCLASSIFIED, 1);
    }

    /**
     * Creates an unclassified single-attempt failure record from a thrown cause.
     *
     * @param stepName the step that failed; non-null
     * @param cause    the failure; non-null; its class name and message are recorded
     * @param at       when the failure was recorded; non-null
     * @return the failure record
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public static SagaFailure of(String stepName, Throwable cause, Instant at) {
        return of(stepName, cause, at, FailureClassification.UNCLASSIFIED, 1);
    }

    /**
     * Creates a failure record from a thrown cause.
     *
     * @param stepName       the step that failed; non-null
     * @param cause          the failure; non-null; its class name and message are recorded
     * @param at             when the failure was recorded; non-null
     * @param classification how the failure was classified; non-null
     * @param attempts       the number of the attempt that failed; at least 1
     * @return the failure record
     * @throws IllegalArgumentException if any reference argument is {@code null} or {@code attempts < 1}
     */
    public static SagaFailure of(String stepName, Throwable cause, Instant at,
            FailureClassification classification, int attempts) {
        if (cause == null) {
            throw new IllegalArgumentException("cause must not be null");
        }
        return new SagaFailure(stepName, cause.getClass().getName(), cause.getMessage(), at, classification,
                attempts);
    }

    /**
     * @param newClassification the classification to record
     * @param newAttempts       the attempt number to record
     * @return this failure with another classification and attempt number
     */
    public SagaFailure classifiedAs(FailureClassification newClassification, int newAttempts) {
        return new SagaFailure(stepName, causeType, message, failedAt, newClassification, newAttempts);
    }
}
