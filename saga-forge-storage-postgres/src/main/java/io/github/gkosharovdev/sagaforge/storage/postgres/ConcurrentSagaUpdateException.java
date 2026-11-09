package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;

/**
 * Thrown by {@link PostgresStorageProvider#save} when the incoming snapshot is not a forward transition from the
 * persisted one, or when it lost a race with a concurrent first save of the same {@code sagaId}
 * (contracts/concurrency-control.md, FR-010, SC-004). The save's transaction is rolled back, so the persisted
 * instance is exactly what the winning writer left.
 *
 * <p>A {@link SagaConcurrencyConflictException}: the engine treats it as "not handled" and never marks the saga
 * {@code FAILED} for it (FR-026).</p>
 */
public final class ConcurrentSagaUpdateException extends SagaConcurrencyConflictException {

    private static final long serialVersionUID = 1L;

    /**
     * @param sagaId the instance whose save was rejected
     * @param rule   the violated rule; the message is {@code "concurrent update of saga <sagaId> rejected: <rule>"}
     */
    public ConcurrentSagaUpdateException(String sagaId, String rule) {
        super(sagaId, "concurrent update of saga " + sagaId + " rejected: " + rule);
    }
}
