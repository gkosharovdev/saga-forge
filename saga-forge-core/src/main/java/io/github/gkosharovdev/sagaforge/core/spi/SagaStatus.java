package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Lifecycle status of a saga instance. {@code COMPLETED}, {@code FAILED}, {@code COMPENSATED} and
 * {@code COMPENSATION_FAILED} are terminal.
 */
public enum SagaStatus {
    CREATED,
    RUNNING,
    /** Compensations are running ({@code specs/006-saga-compensation} FR-014); no forward step runs. */
    COMPENSATING,
    COMPLETED,
    FAILED,
    /** Terminal: every applicable compensation committed ({@code specs/006-saga-compensation} FR-015). */
    COMPENSATED,
    /**
     * Terminal: a compensation failed and was not retried ({@code specs/006-saga-compensation} FR-016). Named
     * {@code COMPENSATION_FAILED} before 0.2.0 ({@code specs/011-saga-outcome-events} FR-019): the status records
     * the fact; what follows it is the observer's decision.
     */
    COMPENSATION_FAILED
}
