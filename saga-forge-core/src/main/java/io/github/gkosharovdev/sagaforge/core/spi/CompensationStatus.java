package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Compensation status of one completed step of a compensating or compensated saga instance
 * ({@code specs/006-saga-compensation} FR-017).
 */
public enum CompensationStatus {

    /** The step has a compensation that has not run yet (or whose unit of work rolled back). */
    NOT_ATTEMPTED,

    /** The step's compensation committed. */
    COMPENSATED,

    /** The step declares no {@code @SagaCompensation}; nothing is undone (FR-007). */
    NOTHING_TO_COMPENSATE,

    /** The step's compensation failed; the instance requires intervention (FR-016). */
    FAILED
}
