package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * PostgreSQL {@link SagaContextStore}: one {@code saga_context} row per {@link SagaContextRecord}, with
 * {@code values} as {@code JSONB} encoded by {@link JsonCodec} (schema in {@code schema.sql},
 * contracts/postgres-schema.md).
 *
 * <p><b>Own connection, own transaction</b> (research.md §6). Every {@link #find(String)},
 * {@link #save(SagaContextRecord)} and {@link #delete(String)} takes a new {@link Connection} from the
 * {@link DataSource}, never accepts or reuses a caller's {@code Connection}, and commits (or rolls back) on its
 * own. A context write therefore never joins a {@code saga_instance} transaction in flight elsewhere, and
 * survives that transaction's rollback (FR-005, SC-002).</p>
 *
 * <p><b>Write-order rule</b> (research.md §8). {@code saga_context.saga_id} references
 * {@code saga_instance.saga_id}, so the instance row must exist, committed, before the first
 * {@code save} for a {@code sagaId}: persist the instance with {@link PostgresStorageProvider#save} first.
 * A {@code save} for a {@code sagaId} with no instance row violates the foreign key and throws
 * {@link UncheckedSqlException}; nothing is written. Deleting the instance through
 * {@link PostgresStorageProvider#delete(String)} also removes its context record ({@code ON DELETE CASCADE}).</p>
 *
 * <p>The caller supplies the {@link DataSource} and owns its pooling. Every {@link SQLException} propagates as
 * {@link UncheckedSqlException}; a {@code values} entry outside the {@link JsonCodec} value set fails with
 * {@link IllegalArgumentException} before any SQL runs. {@code lastModifiedAt} is stored at {@code timestamptz}
 * (microsecond) precision.</p>
 */
public final class PostgresSagaContextStore implements SagaContextStore {

    private static final String VALUES_FIELD = "saga_context.values";

    private static final String UPSERT = "INSERT INTO saga_context (saga_id, values, last_modified_at) "
            + "VALUES (?, ?::jsonb, ?) "
            + "ON CONFLICT (saga_id) DO UPDATE SET "
            + "values = EXCLUDED.values, last_modified_at = EXCLUDED.last_modified_at";
    private static final String FIND = "SELECT saga_id, values, last_modified_at FROM saga_context WHERE saga_id = ?";
    private static final String DELETE = "DELETE FROM saga_context WHERE saga_id = ?";

    private final DataSource dataSource;

    /**
     * @param dataSource where connections come from; the caller owns its pooling
     * @throws IllegalArgumentException if {@code dataSource} is {@code null}
     */
    public PostgresSagaContextStore(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.dataSource = dataSource;
    }

    /**
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     * @throws UncheckedSqlException    if the database fails
     */
    @Override
    public Optional<SagaContextRecord> find(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
        return inOwnTransaction("find saga context " + sagaId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(FIND)) {
                statement.setString(1, sagaId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new SagaContextRecord(row.getString("saga_id"),
                            JsonCodec.decodeObject(VALUES_FIELD, row.getString("values")),
                            SagaInstanceRows.getInstant(row, "last_modified_at")));
                }
            }
        });
    }

    /**
     * Upserts the record's row in its own transaction. The instance row for {@code record.sagaId()} must already
     * exist (write-order rule, see the class Javadoc).
     *
     * @throws IllegalArgumentException if {@code record} is {@code null} or its {@code values} hold an
     *                                  unsupported value
     * @throws UncheckedSqlException    if the database fails, including when no {@code saga_instance} row exists
     *                                  for {@code record.sagaId()}; nothing is written
     */
    @Override
    public void save(SagaContextRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        String values = JsonCodec.encodeObject(VALUES_FIELD, record.values());
        inOwnTransaction("save saga context " + record.sagaId(), connection -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setString(1, record.sagaId());
                statement.setString(2, values);
                SagaInstanceRows.setInstant(statement, 3, record.lastModifiedAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Removes the record's row in its own transaction; the instance row is kept. Deleting an unknown
     * {@code sagaId} is a no-op.
     *
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     * @throws UncheckedSqlException    if the database fails
     */
    @Override
    public void delete(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
        inOwnTransaction("delete saga context " + sagaId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE)) {
                statement.setString(1, sagaId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Runs {@code work} on a new connection with {@code autoCommit = false} and commits; on any failure rolls
     * back and rethrows, wrapping {@link SQLException} in {@link UncheckedSqlException}. Committing explicitly
     * makes the call independent of the pool's default {@code autoCommit}; the original setting is restored
     * before the connection is closed.
     */
    private <T> T inOwnTransaction(String action, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                rollback(connection, e);
                throw e;
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException e) {
            throw new UncheckedSqlException("could not " + action, e);
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // The connection is closed next; a broken connection is discarded by the pool.
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
