package io.github.gkosharovdev.sagaforge.storage.postgres;

/**
 * Thrown by {@link PostgresStorageProvider#save} when the incoming snapshot is not a forward transition from the
 * persisted one, or when it lost a race with a concurrent first save of the same {@code sagaId}
 * (contracts/concurrency-control.md, FR-010, SC-004). The save's transaction is rolled back, so the persisted
 * instance is exactly what the winning writer left.
 *
 * <p>Scoped to this module: {@code saga-forge-core} never references it.</p>
 */
public final class ConcurrentSagaUpdateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sagaId;

    /**
     * @param sagaId the instance whose save was rejected
     * @param rule   the violated rule; the message is {@code "concurrent update of saga <sagaId> rejected: <rule>"}
     */
    public ConcurrentSagaUpdateException(String sagaId, String rule) {
        super("concurrent update of saga " + sagaId + " rejected: " + rule);
        this.sagaId = sagaId;
    }

    /** @return the instance whose save was rejected */
    public String sagaId() {
        return sagaId;
    }
}
