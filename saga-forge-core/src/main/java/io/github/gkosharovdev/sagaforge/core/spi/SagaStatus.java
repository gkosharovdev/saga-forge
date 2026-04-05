package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Lifecycle status of a saga instance.
 */
public enum SagaStatus {
    CREATED,
    RUNNING,
    COMPENSATING,
    COMPLETED,
    FAILED
}
