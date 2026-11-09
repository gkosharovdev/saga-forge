package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Lifecycle status of a saga instance. {@code COMPLETED}, {@code FAILED}, {@code COMPENSATED} and
 * {@code REQUIRES_INTERVENTION} are terminal.
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
    /** Terminal: a compensation failed and was not retried ({@code specs/006-saga-compensation} FR-016). */
    REQUIRES_INTERVENTION
}
