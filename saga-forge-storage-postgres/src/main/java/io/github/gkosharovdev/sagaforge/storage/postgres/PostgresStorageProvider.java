package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;

/**
 * PostgreSQL {@link StorageProvider}: one {@code saga_instance} row per {@link SagaInstance}, one
 * {@code saga_association} row per association (schema in {@code schema.sql}, contracts/postgres-schema.md).
 *
 * <p>Every {@link #save(SagaInstance)} runs in one JDBC transaction on one {@link Connection} with
 * {@code autoCommit = false}: it locks and reads the persisted row ({@code SELECT ... FOR UPDATE}), derives
 * {@code saga_lifecycle_history} rows by diffing that snapshot against the incoming one
 * ({@link LifecycleHistoryDerivation}, data-model.md "Derivation algorithm"), rejects a snapshot that is not a
 * forward transition from it with {@link ConcurrentSagaUpdateException} (contracts/concurrency-control.md), and
 * writes the instance upsert, the association replacement, and the history rows, which commit together or not at
 * all (research.md §1, FR-008, FR-013). The history has no query API; it is inspected with SQL (FR-012). Reads
 * use one statement each, so an instance and its associations come from the same snapshot. {@link #delete(String)} relies on {@code ON DELETE CASCADE} to remove the instance's associations,
 * context, and lifecycle history.</p>
 *
 * <p>The caller supplies the {@link DataSource} and owns its pooling. Every {@link SQLException} propagates as
 * {@link UncheckedSqlException}; a {@code state} value outside the {@link JsonCodec} value set fails with
 * {@link IllegalArgumentException} before anything is written (only the read-only lock and sequence queries
 * have run, and the transaction rolls back). {@code Instant}s are stored at {@code timestamptz}
 * (microsecond) precision.</p>
 */
public final class PostgresStorageProvider implements StorageProvider {

    private static final String UPSERT_INSTANCE = "INSERT INTO saga_instance ("
            + String.join(", ", SagaInstanceRows.COLUMNS) + ") "
            + "VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (saga_id) DO UPDATE SET "
            + SagaInstanceRows.COLUMNS.stream()
                    .filter(column -> !column.equals("saga_id"))
                    .map(column -> column + " = EXCLUDED." + column)
                    .collect(Collectors.joining(", "))
            // xmax is 0 only on a freshly inserted row version; non-zero when ON CONFLICT updated an existing row.
            + " RETURNING (xmax = 0) AS inserted";

    private static final String DELETE_ASSOCIATIONS = "DELETE FROM saga_association WHERE saga_id = ?";

    private static final String INSERT_ASSOCIATION =
            "INSERT INTO saga_association (saga_id, association_property, association_value) VALUES (?, ?, ?)";

    /** The instance columns plus its associations as one JSON object, read in the same statement. */
    private static final String SELECT_INSTANCE = "SELECT " + SagaInstanceRows.selectColumns("i") + ", "
            + "(SELECT COALESCE(jsonb_object_agg(x.association_property, x.association_value), '{}'::jsonb) "
            + "FROM saga_association x WHERE x.saga_id = i.saga_id) AS associations "
            + "FROM saga_instance i ";

    private static final String FIND_BY_ID = SELECT_INSTANCE + "WHERE i.saga_id = ?";

    private static final String FIND_BY_ASSOCIATION = SELECT_INSTANCE
            + "JOIN saga_association a ON a.saga_id = i.saga_id "
            + "WHERE a.association_property = ? AND a.association_value = ? "
            + "ORDER BY i.saga_id";

    /** Locks the instance row (not its associations) until the save's transaction ends. */
    private static final String LOCK_PREVIOUS = FIND_BY_ID + " FOR UPDATE OF i";

    private static final String NEXT_SEQUENCE_NUMBER =
            "SELECT COALESCE(MAX(sequence_number) + 1, 0) FROM saga_lifecycle_history WHERE saga_id = ?";

    private static final String INSERT_HISTORY = "INSERT INTO saga_lifecycle_history (saga_id, sequence_number, "
            + "kind, step_name, touched_aggregates, outcome_step, failure_step_name, failure_cause_type, "
            + "failure_message, failure_failed_at, occurred_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)";

    private static final String TOUCHED_AGGREGATES_FIELD = "saga_lifecycle_history.touched_aggregates";

    private static final String DELETE_INSTANCE = "DELETE FROM saga_instance WHERE saga_id = ?";

    private static final String ASSOCIATIONS_FIELD = "saga_association";

    private final DataSource dataSource;

    /**
     * @param dataSource where connections come from; the caller owns its pooling
     * @throws IllegalArgumentException if {@code dataSource} is {@code null}
     */
    public PostgresStorageProvider(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.dataSource = dataSource;
    }

    /**
     * Upserts the instance row, replaces its association rows, and appends the lifecycle-history rows derived
     * from the previously persisted snapshot, in one transaction. Re-saving an unchanged instance adds no
     * history row.
     *
     * <p>Concurrency control (contracts/concurrency-control.md, FR-010): the persisted row is locked
     * {@code FOR UPDATE} and {@code instance} must be a forward transition from it
     * ({@link LifecycleHistoryDerivation#requireForwardTransition}), checked before any write. When no row exists
     * yet there is nothing to lock, so a first save whose upsert finds a row created meanwhile by a concurrent
     * first save is rejected, even when both snapshots are identical ({@code .specify/memory/memory.md}
     * G-003-03).</p>
     *
     * @throws IllegalArgumentException      if {@code instance} is {@code null}, a required field of it is
     *                                       {@code null}, or its {@code state} holds an unsupported value
     * @throws ConcurrentSagaUpdateException if {@code instance} is not a forward transition from the persisted
     *                                       snapshot, or lost a concurrent first save; nothing is written
     * @throws UncheckedSqlException         if the database fails; nothing is written
     */
    @Override
    public void save(SagaInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        SagaInstanceRows.requireStorable(instance);
        inTransaction("save saga instance " + instance.sagaId(), connection -> {
            SagaInstance previous = lockPrevious(connection, instance.sagaId());
            if (previous != null) {
                LifecycleHistoryDerivation.requireForwardTransition(previous, instance);
            }
            long next = nextSequenceNumber(connection, instance.sagaId());
            List<SagaLifecycleEntry> entries = LifecycleHistoryDerivation.derive(previous, instance, next);
            boolean inserted = upsertInstance(connection, instance);
            if (previous == null && !inserted) {
                // No row to lock was read, yet the upsert hit one: a concurrent first save committed it meanwhile.
                throw new ConcurrentSagaUpdateException(instance.sagaId(),
                        "a concurrent first save created the instance after this save found no row");
            }
            replaceAssociations(connection, instance);
            insertHistory(connection, entries);
        });
    }

    /**
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     * @throws UncheckedSqlException    if the database fails
     */
    @Override
    public Optional<SagaInstance> findById(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
        List<SagaInstance> found = query("find saga instance " + sagaId, FIND_BY_ID, sagaId);
        return found.stream().findFirst();
    }

    /**
     * @return the matching instances ordered by {@code saga_id}; never {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     * @throws UncheckedSqlException    if the database fails
     */
    @Override
    public List<SagaInstance> findByAssociation(String associationProperty, String associationValue) {
        if (associationProperty == null) {
            throw new IllegalArgumentException("associationProperty must not be null");
        }
        if (associationValue == null) {
            throw new IllegalArgumentException("associationValue must not be null");
        }
        return query("find saga instances by " + associationProperty + "=" + associationValue,
                FIND_BY_ASSOCIATION, associationProperty, associationValue);
    }

    /**
     * Deletes the instance row; the schema's cascades remove its associations, context, and lifecycle history.
     * Deleting an unknown {@code sagaId} is a no-op.
     *
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     * @throws UncheckedSqlException    if the database fails
     */
    @Override
    public void delete(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_INSTANCE)) {
            statement.setString(1, sagaId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new UncheckedSqlException("could not delete saga instance " + sagaId, e);
        }
    }

    /** @return the persisted snapshot, its row locked {@code FOR UPDATE}, or {@code null} if there is none */
    private static SagaInstance lockPrevious(Connection connection, String sagaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_PREVIOUS)) {
            statement.setString(1, sagaId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return SagaInstanceRows.fromRow(row, decodeAssociations(row.getString("associations")));
            }
        }
    }

    private static long nextSequenceNumber(Connection connection, String sagaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(NEXT_SEQUENCE_NUMBER)) {
            statement.setString(1, sagaId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void insertHistory(Connection connection, List<SagaLifecycleEntry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_HISTORY)) {
            for (SagaLifecycleEntry entry : entries) {
                insert.setString(1, entry.sagaId());
                insert.setLong(2, entry.sequenceNumber());
                insert.setString(3, entry.kind().name());
                insert.setString(4, entry.stepName());
                insert.setString(5, SagaInstanceRows.encodeTouchedAggregates(TOUCHED_AGGREGATES_FIELD,
                        entry.touchedAggregates()));
                insert.setString(6, entry.outcomeStep());
                SagaInstanceRows.bindFailure(insert, 7, entry.failure());
                SagaInstanceRows.setInstant(insert, 11, entry.occurredAt());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** @return {@code true} if the upsert inserted a new row, {@code false} if it updated an existing one */
    private static boolean upsertInstance(Connection connection, SagaInstance instance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_INSTANCE)) {
            SagaInstanceRows.bindInstance(statement, instance);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getBoolean("inserted");
            }
        }
    }

    private static void replaceAssociations(Connection connection, SagaInstance instance) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(DELETE_ASSOCIATIONS)) {
            delete.setString(1, instance.sagaId());
            delete.executeUpdate();
        }
        if (instance.associations().isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_ASSOCIATION)) {
            for (Map.Entry<String, String> association : instance.associations().entrySet()) {
                insert.setString(1, instance.sagaId());
                insert.setString(2, association.getKey());
                insert.setString(3, association.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private List<SagaInstance> query(String action, String sql, String... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            List<SagaInstance> instances = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    instances.add(SagaInstanceRows.fromRow(rows, decodeAssociations(rows.getString("associations"))));
                }
            }
            return List.copyOf(instances);
        } catch (SQLException e) {
            throw new UncheckedSqlException("could not " + action, e);
        }
    }

    private static Map<String, String> decodeAssociations(String json) {
        Map<String, Object> decoded = JsonCodec.decodeObject(ASSOCIATIONS_FIELD, json);
        for (Map.Entry<String, Object> entry : decoded.entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                throw new IllegalStateException(ASSOCIATIONS_FIELD + "." + entry.getKey() + " must hold a String");
            }
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, String> associations = (Map) decoded;
        return associations;
    }

    /**
     * Runs {@code work} on one connection with {@code autoCommit = false} and commits; on any failure rolls back
     * and rethrows, wrapping only {@link SQLException} in {@link UncheckedSqlException}; a
     * {@link RuntimeException} (such as {@link ConcurrentSagaUpdateException}) propagates unwrapped. Restores the
     * connection's {@code autoCommit} before closing it, for pooled connections.
     */
    private void inTransaction(String action, SqlWork work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
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
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
