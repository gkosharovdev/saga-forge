package io.github.gkosharovdev.sagaforge.core.failure;

/**
 * How the engine classifies the failure that rolled back a saga-scoped unit of work
 * ({@code specs/005-step-retry} FR-008).
 *
 * <p>Only {@link #TRANSIENT} failures are retried. A persisted {@code FAILED} instance never carries
 * {@code TRANSIENT}: a transient failure on the last allowed attempt is recorded as {@link #EXHAUSTED}.</p>
 */
public enum FailureClassification {

    /** May succeed on a later attempt: marked {@link TransientFailure}, or listed in the step's {@code retryOn}. */
    TRANSIENT,

    /** Declared final by the author through {@link FinalFailure}; never retried. */
    FINAL,

    /** Transient, but the step's retry policy allowed no further attempt. */
    EXHAUSTED,

    /**
     * Neither marked nor listed as retryable (a programming error, for example, or any {@link Error}); never
     * retried. Kept apart from {@link #FINAL} so that later features, such as compensation, can react only to
     * failures an author chose to treat as final.
     */
    UNCLASSIFIED
}
