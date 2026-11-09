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

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;

/**
 * PostgreSQL {@link StorageProvider}: one {@code saga_instance} row per {@link SagaInstance}, one
 * {@code saga_association} row per association (schema in {@code schema.sql}, contracts/postgres-schema.md).
 *
 * <p>Every {@link #save(SagaInstance, List)} runs in one JDBC transaction on one {@link Connection} with
 * {@code autoCommit = false}: it locks and reads the persisted row ({@code SELECT ... FOR UPDATE}), rejects a
 * snapshot that is not a forward transition from it with {@link ConcurrentSagaUpdateException}
 * ({@link ForwardTransition}, contracts/concurrency-control.md), and writes the instance upsert, the association
 * replacement, and the {@code saga_lifecycle_history} rows of the entries handed over, which commit together or
 * not at all. The provider persists exactly the entries it is given, numbered {@code MAX + 1} onwards in list
 * order ({@link LifecycleEntryRows}); it never derives history from snapshots. The history has no query API; it is
 * inspected with SQL (FR-012). Reads use one statement each, so an instance and its associations come from the
 * same snapshot. {@link #delete(String)} relies on {@code ON DELETE CASCADE} to remove the instance's
 * associations, context, and lifecycle history.</p>
 *
 * <p><b>Joining</b> (FR-022, FR-023, contracts/storage-provider.md). Built with a {@link TransactionBoundary}, the
 * provider runs {@link #save(SagaInstance, List)}, {@link #findById(String)}, {@link #findByAssociation} and
 * {@link #delete(String)} on the active transaction's {@code resource(Connection.class)} while one is active on the
 * calling thread: it never commits, rolls back, changes {@code autoCommit} on or closes that connection, and on a
 * {@link SQLException} it marks the transaction rollback-only before throwing {@link UncheckedSqlException}. An
 * active transaction without a {@code java.sql.Connection} resource is an {@link IllegalStateException}. With no
 * active transaction, or built from a {@link DataSource} only, every operation runs on a connection of its own as in
 * 003: {@code save} in its own transaction, reads and {@code delete} in auto-commit mode.</p>
 *
 * <p>{@link #findByIdAndLock(String)} does not lock yet and always throws {@link IllegalStateException}
 * (specs/004-saga-engine T074 adds it).</p>
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

    private static final String DELETE_INSTANCE = "DELETE FROM saga_instance WHERE saga_id = ?";

    private static final String ASSOCIATIONS_FIELD = "saga_association";

    private final DataSource dataSource;
    private final Optional<TransactionBoundary> boundary;

    /**
     * A provider that never joins: every operation runs on a connection of its own (003 behavior).
     *
     * @param dataSource where connections come from; the caller owns its pooling
     * @throws IllegalArgumentException if {@code dataSource} is {@code null}
     */
    public PostgresStorageProvider(DataSource dataSource) {
        this(requireDataSource(dataSource), Optional.empty());
    }

    /**
     * A provider that joins {@code boundary}'s active transaction, and runs on a connection of its own otherwise.
     * The host binds connections of the same {@code dataSource}.
     *
     * @param dataSource where connections come from when no transaction is active; the caller owns its pooling
     * @param boundary   whose active transaction every operation joins
     * @throws IllegalArgumentException if an argument is {@code null}
     */
    public PostgresStorageProvider(DataSource dataSource, TransactionBoundary boundary) {
        this(requireDataSource(dataSource), Optional.of(requireBoundary(boundary)));
    }

    private PostgresStorageProvider(DataSource dataSource, Optional<TransactionBoundary> boundary) {
        this.dataSource = dataSource;
        this.boundary = boundary;
    }

    /**
     * Upserts the instance row, replaces its association rows, and appends one lifecycle-history row per entry of
     * {@code newEntries}, in one transaction. The rows are numbered consecutively from the instance's current
     * {@code MAX(sequence_number) + 1}, in list order; nothing is derived, so a save with no entries adds no
     * history row.
     *
     * <p>Concurrency control (contracts/concurrency-control.md, FR-010): the persisted row is locked
     * {@code FOR UPDATE} and {@code instance} must be a forward transition from it
     * ({@link ForwardTransition#requireForwardTransition}), checked before any write. When no row exists
     * yet there is nothing to lock, so a first save whose upsert finds a row created meanwhile by a concurrent
     * first save is rejected, even when both snapshots are identical ({@code .specify/memory/memory.md}
     * G-003-03).</p>
     *
     * @throws IllegalArgumentException      if {@code instance} or {@code newEntries} is {@code null},
     *                                       {@code newEntries} contains {@code null} or an entry of another
     *                                       {@code sagaId}, a required field of {@code instance} is {@code null},
     *                                       or its {@code state} holds an unsupported value; checked before any SQL
     * @throws ConcurrentSagaUpdateException if {@code instance} is not a forward transition from the persisted
     *                                       snapshot, or lost a concurrent first save; nothing is written (joined:
     *                                       the caller's transaction decides)
     * @throws UncheckedSqlException         if the database fails; nothing is written (joined: the transaction is
     *                                       marked rollback-only)
     * @throws IllegalStateException         if joined and the active transaction has no {@code Connection}
     */
    @Override
    public void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries) {
        requireEntriesOf(instance, newEntries);
        SagaInstanceRows.requireStorable(instance);
        inJoinedOrOwnTransaction("save saga instance " + instance.sagaId(), connection -> {
            SagaInstance previous = lockPrevious(connection, instance.sagaId());
            if (previous != null) {
                ForwardTransition.requireForwardTransition(previous, instance);
            }
            long next = nextSequenceNumber(connection, instance.sagaId());
            boolean inserted = upsertInstance(connection, instance);
            if (previous == null && !inserted) {
                // No row to lock was read, yet the upsert hit one: a concurrent first save committed it meanwhile.
                throw new ConcurrentSagaUpdateException(instance.sagaId(),
                        "a concurrent first save created the instance after this save found no row");
            }
            replaceAssociations(connection, instance);
            LifecycleEntryRows.insertAll(connection, newEntries, next);
            return null;
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
        return onJoinedOrOwnConnection("find saga instance " + sagaId,
                connection -> select(connection, FIND_BY_ID, sagaId)).stream().findFirst();
    }

    /**
     * Always fails: built without a {@code TransactionBoundary} there is no joined transaction to hold a lock in
     * (contracts/storage-provider.md); with one, locking arrives with specs/004-saga-engine T074.
     *
     * @throws IllegalArgumentException if {@code sagaId} is {@code null}
     * @throws IllegalStateException    otherwise, before any SQL runs
     */
    @Override
    public Optional<SagaInstance> findByIdAndLock(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
        String reason = boundary.isEmpty() ? "it was built without a TransactionBoundary"
                : "locking is not supported yet";
        throw new IllegalStateException("PostgresStorageProvider cannot lock saga instance " + sagaId + ": " + reason);
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
        return onJoinedOrOwnConnection("find saga instances by " + associationProperty + "=" + associationValue,
                connection -> select(connection, FIND_BY_ASSOCIATION, associationProperty, associationValue));
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
        onJoinedOrOwnConnection("delete saga instance " + sagaId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_INSTANCE)) {
                statement.setString(1, sagaId);
                statement.executeUpdate();
            }
            return null;
        });
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

    private static void requireEntriesOf(SagaInstance instance, List<SagaLifecycleEntry> newEntries) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        if (newEntries == null) {
            throw new IllegalArgumentException("newEntries must not be null");
        }
        for (SagaLifecycleEntry entry : newEntries) {
            if (entry == null) {
                throw new IllegalArgumentException("newEntries must not contain null elements");
            }
            if (!entry.sagaId().equals(instance.sagaId())) {
                throw new IllegalArgumentException("lifecycle entry of saga " + entry.sagaId()
                        + " cannot be saved with saga instance " + instance.sagaId());
            }
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

    private static List<SagaInstance> select(Connection connection, String sql, String... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

    /** Runs {@code work} on the joined connection, else in a transaction of its own. */
    private <T> T inJoinedOrOwnTransaction(String action, SqlWork<T> work) {
        Optional<LocalTransaction> joined = joinedTransaction();
        return joined.isPresent() ? onJoined(joined.get(), action, work) : inOwnTransaction(action, work);
    }

    /** Runs {@code work} on the joined connection, else on a connection of its own in auto-commit mode. */
    private <T> T onJoinedOrOwnConnection(String action, SqlWork<T> work) {
        Optional<LocalTransaction> joined = joinedTransaction();
        return joined.isPresent() ? onJoined(joined.get(), action, work) : onOwnConnection(action, work);
    }

    private Optional<LocalTransaction> joinedTransaction() {
        return boundary.flatMap(TransactionBoundary::current);
    }

    /**
     * Runs {@code work} on {@code transaction}'s connection, leaving the connection's lifecycle to the transaction;
     * a {@link SQLException} marks the transaction rollback-only and is wrapped in {@link UncheckedSqlException}.
     */
    private static <T> T onJoined(LocalTransaction transaction, String action, SqlWork<T> work) {
        Connection connection = transaction.resource(Connection.class).orElseThrow(() -> new IllegalStateException(
                "The active transaction has no java.sql.Connection resource; PostgresStorageProvider cannot join it"
                        + " to " + action));
        try {
            return work.run(connection);
        } catch (SQLException e) {
            transaction.setRollbackOnly();
            throw new UncheckedSqlException("could not " + action, e);
        }
    }

    /** Runs {@code work} on a connection of its own, in the connection's default (auto-commit) mode. */
    private <T> T onOwnConnection(String action, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new UncheckedSqlException("could not " + action, e);
        }
    }

    /**
     * Runs {@code work} on one connection with {@code autoCommit = false} and commits; on any failure rolls back
     * and rethrows, wrapping only {@link SQLException} in {@link UncheckedSqlException}; a
     * {@link RuntimeException} (such as {@link ConcurrentSagaUpdateException}) propagates unwrapped. Restores the
     * connection's {@code autoCommit} before closing it, for pooled connections.
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

    private static DataSource requireDataSource(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        return dataSource;
    }

    private static TransactionBoundary requireBoundary(TransactionBoundary boundary) {
        if (boundary == null) {
            throw new IllegalArgumentException("boundary must not be null");
        }
        return boundary;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
