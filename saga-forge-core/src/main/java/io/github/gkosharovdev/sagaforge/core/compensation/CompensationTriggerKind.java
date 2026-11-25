package io.github.gkosharovdev.sagaforge.core.compensation;

/**
 * What started compensation for a saga instance ({@code specs/006-saga-compensation} FR-001, FR-006, FR-006a).
 */
public enum CompensationTriggerKind {

    /** A {@code @StartCompensation} step handled a business-failure event and its unit of work committed. */
    FAILURE_OUTCOME,

    /** A step failed with a failure classified {@code FINAL} after an earlier unit of work committed. */
    FINAL_FAILURE,

    /** A step's transient failure persisted through every attempt its retry policy allows ({@code EXHAUSTED}). */
    RETRIES_EXHAUSTED,

    /**
     * An outbox command a step sent failed for good, {@code FINAL} or {@code EXHAUSTED} once the transport's attempts
     * were used up ({@code specs/012-outbox-command-failure-feedback} FR-008).
     */
    COMMAND_FAILED,

    /**
     * A {@code @StartCompensation} step that handles a deadline's expiry ({@code @DeadlineExpired}) ran and its unit of
     * work committed ({@code specs/017-saga-deadlines} FR-015).
     */
    DEADLINE_EXPIRED
}
