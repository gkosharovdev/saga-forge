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
    RETRIES_EXHAUSTED
}
