package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Thrown by a {@link StorageProvider} write that detects that the stored saga instance changed concurrently;
 * nothing was written.
 *
 * <p>The engine treats it as "not handled", never as a saga failure: it rolls the unit of work back, writes no
 * {@code FAILED} record, and reports {@link HandlingOutcome#NOT_HANDLED} so the transport can redeliver the event.
 * Storage modules may throw a subclass (Postgres: {@code ConcurrentSagaUpdateException}).</p>
 */
public class SagaConcurrencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sagaId;

    /**
     * Creates the exception.
     *
     * @param sagaId  the instance whose write conflicted
     * @param message the detail message
     */
    public SagaConcurrencyConflictException(String sagaId, String message) {
        super(message);
        this.sagaId = sagaId;
    }

    /**
     * Returns the instance whose write conflicted.
     *
     * @return the saga instance identifier
     */
    public String sagaId() {
        return sagaId;
    }
}
