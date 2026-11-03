package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;

/**
 * Persisted description of the failure that moved a saga instance to {@link SagaStatus#FAILED}.
 *
 * @param stepName  the step that failed; non-null
 * @param causeType fully qualified class name of the failure; non-null
 * @param message   the failure's message; nullable
 * @param failedAt  when the failure was recorded; non-null
 * @throws IllegalArgumentException if {@code stepName}, {@code causeType}, or {@code failedAt} is {@code null}
 */
public record SagaFailure(String stepName, String causeType, String message, Instant failedAt) {

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
    }

    /**
     * Creates a failure record from a thrown cause.
     *
     * @param stepName the step that failed; non-null
     * @param cause    the failure; non-null; its class name and message are recorded
     * @param at       when the failure was recorded; non-null
     * @return the failure record
     * @throws IllegalArgumentException if any argument is {@code null}
     */
    public static SagaFailure of(String stepName, Throwable cause, Instant at) {
        if (cause == null) {
            throw new IllegalArgumentException("cause must not be null");
        }
        return new SagaFailure(stepName, cause.getClass().getName(), cause.getMessage(), at);
    }
}
