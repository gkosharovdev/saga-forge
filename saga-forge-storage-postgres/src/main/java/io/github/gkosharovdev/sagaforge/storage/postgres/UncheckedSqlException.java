package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.sql.SQLException;

/**
 * Unchecked wrapper for a JDBC driver failure.
 *
 * <p>Every {@link SQLException} raised while this module talks to PostgreSQL propagates as this type, as the
 * {@link io.github.gkosharovdev.sagaforge.core.spi.StorageProvider} and
 * {@link io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore} Javadoc require ("storage failures
 * propagate as unchecked exceptions"). The original {@link SQLException} is kept as the cause, so its SQL
 * state and vendor code stay available. This includes the documented write-order failure: saving a
 * {@code SagaContextRecord} for a {@code sagaId} with no saga instance row violates the {@code saga_context}
 * foreign key and surfaces as this exception.</p>
 */
public class UncheckedSqlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what the module was doing when the driver failed
     * @param cause   the driver failure
     */
    public UncheckedSqlException(String message, SQLException cause) {
        super(message, cause);
    }

    /**
     * @return the driver failure
     */
    @Override
    public synchronized SQLException getCause() {
        return (SQLException) super.getCause();
    }
}
