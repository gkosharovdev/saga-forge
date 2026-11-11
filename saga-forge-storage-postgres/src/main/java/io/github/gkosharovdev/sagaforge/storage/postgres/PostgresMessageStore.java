package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.ConsumerHeartbeat;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.DuplicateMessageException;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaseLostException;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.UnreadableMessageException;

/**
 * {@link MessageStore} over the {@code saga_message_store} and {@code saga_message_store_consumer} tables of
 * {@code schema.sql} ({@code specs/007-in-memory-transport} contracts/postgres-message-log.md).
 *
 * <ul>
 *   <li>Methods taking a {@link LocalTransaction} use its {@code java.sql.Connection} resource, as
 *       {@link PostgresStorageProvider} does when it joins the engine's transaction; a failure marks that transaction
 *       rollback-only.</li>
 *   <li>Every other method runs in a short JDBC transaction of its own on a connection from the {@link DataSource}.</li>
 *   <li>Payloads are JSON, read and written field by field with the payload class loaded from the configured class
 *       loader; a payload that cannot be read comes back in {@link LeasedMessages#unreadable()}.</li>
 *   <li>Leasing is one statement: due, unleased heads of their ordering key, locked with {@code FOR UPDATE SKIP LOCKED}
 *       and leased in the same {@code UPDATE}.</li>
 * </ul>
 *
 * <p>SQL failures propagate as {@link UncheckedSqlException}. Thread-safe.</p>
 */
public class PostgresMessageStore implements MessageStore {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String COLUMNS = "position, message_id, kind, payload_type, payload, command_id, ordering_key,"
            + " source_saga_type, source_saga_id, recorded_at, status, attempts, next_attempt_at, last_failure,"
            + " leased_by, leased_until, consumed_at, set_aside_reason, external_ref";

    private final DataSource dataSource;
    private final ClassLoader payloadClassLoader;

    /**
     * @param dataSource   the database holding saga state; non-null
     * @param transactions the boundary whose transactions {@link #append} joins; non-null (kept for symmetry with
     *                     {@link PostgresStorageProvider}; transactions are passed explicitly)
     */
    public PostgresMessageStore(DataSource dataSource, TransactionBoundary transactions) {
        this(dataSource, transactions, Thread.currentThread().getContextClassLoader());
    }

    /**
     * @param payloadClassLoader loads payload classes when reading; non-null
     */
    public PostgresMessageStore(DataSource dataSource, TransactionBoundary transactions,
            ClassLoader payloadClassLoader) {
        if (dataSource == null || transactions == null || payloadClassLoader == null) {
            throw new IllegalArgumentException("dataSource, transactions and payloadClassLoader must not be null");
        }
        this.dataSource = dataSource;
        this.payloadClassLoader = payloadClassLoader;
    }

    // ----- append -------------------------------------------------------------------------------------------

    @Override
    public void append(LocalTransaction tx, List<NewMessage> entries) {
        insert(tx, entries, null, true);
    }

    /**
     * Appends {@code entry} without its payload, as Spring Modulith interop's delivery record whose payload the
     * registry holds ({@code specs/007-in-memory-transport} FR-028a).
     *
     * @param externalRef the registry's publication identity; non-blank
     */
    public void appendDeliveryRecord(LocalTransaction tx, NewMessage entry, String externalRef) {
        if (externalRef == null || externalRef.isBlank()) {
            throw new IllegalArgumentException("externalRef must not be blank");
        }
        insert(tx, List.of(entry), externalRef, false);
    }

    private void insert(LocalTransaction tx, List<NewMessage> entries, String externalRef, boolean withPayload) {
        if (tx == null) {
            throw new IllegalArgumentException("tx must not be null");
        }
        if (entries.isEmpty()) {
            return;
        }
        Connection connection = tx.resource(Connection.class).orElseThrow(() -> new IllegalStateException(
                "The active transaction has no java.sql.Connection resource; PostgresMessageStore cannot join it"));
        String sql = "INSERT INTO saga_message_store (message_id, kind, payload_type, payload, command_id, ordering_key,"
                + " source_saga_type, source_saga_id, recorded_at, status, attempts, next_attempt_at, external_ref)"
                + " VALUES (?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?, ?, ?, ?, 'UNCONSUMED', 0, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (NewMessage entry : entries) {
                statement.setString(1, entry.messageId());
                statement.setString(2, entry.kind().name());
                statement.setString(3, entry.payloadType());
                statement.setString(4, withPayload ? PayloadCodec.encode(entry.payload()) : null);
                statement.setString(5, entry.commandId() == null ? null
                        : PayloadCodec.encodeCommandId(entry.commandId()));
                statement.setString(6, entry.orderingKey());
                statement.setString(7, entry.sourceSagaType());
                statement.setString(8, entry.sourceSagaId());
                statement.setObject(9, utc(entry.recordedAt()));
                statement.setObject(10, utc(entry.recordedAt()));
                statement.setString(11, externalRef);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            tx.setRollbackOnly();
            if (isUniqueViolation(e)) {
                throw new DuplicateMessageException(duplicatedId(entries, e));
            }
            throw new UncheckedSqlException("could not append " + entries.size() + " message(s)", e);
        } catch (RuntimeException e) {
            tx.setRollbackOnly();
            throw e;
        }
    }

    /**
     * The external reference of a delivery record (Spring Modulith interop, FR-028a).
     *
     * @return the registry publication identity of {@code messageId}, if it has one
     */
    public java.util.Optional<String> externalRef(String messageId) {
        return inOwnTransaction("read the external reference of " + messageId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT external_ref FROM saga_message_store WHERE message_id = ?")) {
                statement.setString(1, messageId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? java.util.Optional.ofNullable(resultSet.getString(1))
                            : java.util.Optional.<String>empty();
                }
            }
        });
    }

    // ----- leasing and leaseholder writes --------------------------------------------------------------------

    @Override
    public LeasedMessages lease(String consumerId, Instant now, Duration leaseDuration, int limit) {
        String sql = "WITH due AS (SELECT m.position FROM saga_message_store m"
                + " WHERE m.status = 'UNCONSUMED' AND m.next_attempt_at <= ?"
                + " AND (m.leased_until IS NULL OR m.leased_until <= ?)"
                + " AND NOT EXISTS (SELECT 1 FROM saga_message_store e WHERE e.ordering_key = m.ordering_key"
                + " AND e.status = 'UNCONSUMED' AND e.position < m.position)"
                + " ORDER BY m.position LIMIT ? FOR UPDATE SKIP LOCKED)"
                + " UPDATE saga_message_store m SET leased_by = ?, leased_until = ?, attempts = m.attempts + 1"
                + " FROM due WHERE m.position = due.position RETURNING " + qualified("m");
        List<StoredMessage> rows = inOwnTransaction("lease messages", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, utc(now));
                statement.setObject(2, utc(now));
                statement.setInt(3, limit);
                statement.setString(4, consumerId);
                statement.setObject(5, utc(now.plus(leaseDuration)));
                return readAll(statement, true);
            }
        });
        rows = new ArrayList<>(rows);
        rows.sort(Comparator.comparingLong(StoredMessage::position));
        List<StoredMessage> readable = new ArrayList<>();
        List<UnreadableMessageException> unreadable = new ArrayList<>();
        for (StoredMessage row : rows) {
            if (row.externalRef() != null) {
                readable.add(row.withoutPayload());
                continue;
            }
            try {
                readable.add(row.withPayload(decode(row.payloadType(), (String) row.payload())));
            } catch (ClassNotFoundException | RuntimeException e) {
                unreadable.add(new UnreadableMessageException(row, e));
            }
        }
        return new LeasedMessages(readable, unreadable);
    }

    @Override
    public Set<String> renew(String consumerId, Collection<String> messageIds, Instant until) {
        if (messageIds.isEmpty()) {
            return Set.of();
        }
        Set<String> renewed = inOwnTransaction("renew leases", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE saga_message_store"
                    + " SET leased_until = ? WHERE leased_by = ? AND status = 'UNCONSUMED'"
                    + " AND message_id = ANY (?) RETURNING message_id")) {
                Array ids = connection.createArrayOf("varchar", messageIds.toArray());
                statement.setObject(1, utc(until));
                statement.setString(2, consumerId);
                statement.setArray(3, ids);
                Set<String> found = new HashSet<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        found.add(resultSet.getString(1));
                    }
                }
                return found;
            }
        });
        Set<String> lost = new HashSet<>(messageIds);
        lost.removeAll(renewed);
        return lost;
    }

    @Override
    public void markConsumed(String consumerId, String messageId, Instant at) {
        inOwnTransaction("mark message " + messageId + " consumed", connection -> {
            consume(connection, consumerId, messageId, at);
            return null;
        });
    }

    @Override
    public void markConsumed(LocalTransaction tx, String consumerId, String messageId, Instant at) {
        Connection connection = tx.resource(Connection.class).orElseThrow(() -> new IllegalStateException(
                "The active transaction has no java.sql.Connection resource; PostgresMessageStore cannot join it"));
        try {
            consume(connection, consumerId, messageId, at);
        } catch (SQLException e) {
            tx.setRollbackOnly();
            throw new UncheckedSqlException("could not mark message " + messageId + " consumed", e);
        } catch (RuntimeException e) {
            tx.setRollbackOnly();
            throw e;
        }
    }

    private static void consume(Connection connection, String consumerId, String messageId, Instant at)
            throws SQLException {
        holderUpdate(connection, consumerId, messageId, "status = 'CONSUMED', consumed_at = ?, leased_by = NULL,"
                + " leased_until = NULL", statement -> statement.setObject(1, utc(at)));
    }

    @Override
    public void recordFailure(String consumerId, String messageId, DeliveryFailure failure, Instant nextAttemptAt) {
        inOwnTransaction("record a failure of message " + messageId, connection -> {
            holderUpdate(connection, consumerId, messageId, "last_failure = CAST(? AS JSONB), next_attempt_at = ?,"
                    + " leased_by = NULL, leased_until = NULL", statement -> {
                        statement.setString(1, PayloadCodec.encodeFailure(failure));
                        statement.setObject(2, utc(nextAttemptAt));
                    });
            return null;
        });
    }

    @Override
    public void setAside(String consumerId, String messageId, DeliveryFailure failure, String reason) {
        String trimmed = reason.length() > 4096 ? reason.substring(0, 4096) : reason;
        inOwnTransaction("set message " + messageId + " aside", connection -> {
            holderUpdate(connection, consumerId, messageId, "status = 'SET_ASIDE', set_aside_reason = ?,"
                    + " last_failure = CAST(? AS JSONB), leased_by = NULL, leased_until = NULL", statement -> {
                        statement.setString(1, trimmed);
                        statement.setString(2, PayloadCodec.encodeFailure(failure));
                    });
            return null;
        });
    }

    @Override
    public void release(String consumerId) {
        inOwnTransaction("release the leases of " + consumerId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE saga_message_store"
                    + " SET leased_by = NULL, leased_until = NULL WHERE leased_by = ?")) {
                statement.setString(1, consumerId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    // ----- operator -----------------------------------------------------------------------------------------

    @Override
    public List<StoredMessage> query(MessageQuery query) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM saga_message_store WHERE TRUE");
        List<Object> params = new ArrayList<>();
        if (!query.statuses().isEmpty()) {
            sql.append(" AND status IN (")
                    .append(query.statuses().stream().map(s -> "?").collect(Collectors.joining(", "))).append(")");
            query.statuses().stream().map(Enum::name).sorted().forEach(params::add);
        }
        if (query.sourceSagaId() != null) {
            sql.append(" AND source_saga_type = ? AND source_saga_id = ?");
            params.add(query.sourceSagaType());
            params.add(query.sourceSagaId());
        }
        if (query.orderingKey() != null) {
            sql.append(" AND ordering_key = ?");
            params.add(query.orderingKey());
        }
        if (query.payloadType() != null) {
            sql.append(" AND payload_type = ?");
            params.add(query.payloadType());
        }
        sql.append(" ORDER BY position LIMIT ?");
        params.add(query.limit());
        List<StoredMessage> rows = inOwnTransaction("query messages", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    statement.setObject(i + 1, params.get(i));
                }
                return readAll(statement, query.includePayload());
            }
        });
        if (!query.includePayload()) {
            return rows;
        }
        List<StoredMessage> decoded = new ArrayList<>();
        for (StoredMessage row : rows) {
            if (!row.hasPayload()) {
                continue;
            }
            try {
                decoded.add(row.withPayload(decode(row.payloadType(), (String) row.payload())));
            } catch (ClassNotFoundException | RuntimeException unreadable) {
                // Left out: a diagnostic query never fails on an unreadable payload.
            }
        }
        return decoded;
    }

    @Override
    public boolean redeliver(String messageId, Instant now) {
        return inOwnTransaction("redeliver message " + messageId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE saga_message_store"
                    + " SET status = 'UNCONSUMED', attempts = 0, next_attempt_at = ?, set_aside_reason = NULL"
                    + " WHERE message_id = ? AND status = 'SET_ASIDE'")) {
                statement.setObject(1, utc(now));
                statement.setString(2, messageId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int purgeConsumed(Instant cutoff, int batchSize) {
        return inOwnTransaction("purge consumed messages", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM saga_message_store"
                    + " WHERE position IN (SELECT position FROM saga_message_store WHERE status = 'CONSUMED'"
                    + " AND consumed_at < ? ORDER BY position LIMIT ?)")) {
                statement.setObject(1, utc(cutoff));
                statement.setInt(2, batchSize);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public void heartbeat(ConsumerHeartbeat heartbeat) {
        inOwnTransaction("write the heartbeat of " + heartbeat.consumerId(), connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO saga_message_store_consumer"
                    + " (consumer_id, host, started_at, last_seen_at) VALUES (?, ?, ?, ?) ON CONFLICT (consumer_id)"
                    + " DO UPDATE SET host = EXCLUDED.host, last_seen_at = EXCLUDED.last_seen_at")) {
                statement.setString(1, heartbeat.consumerId());
                statement.setString(2, heartbeat.host());
                statement.setObject(3, utc(heartbeat.startedAt()));
                statement.setObject(4, utc(heartbeat.lastSeenAt()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ConsumerHeartbeat> liveConsumers(Instant since) {
        return inOwnTransaction("list live consumers", connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT consumer_id, host, started_at,"
                    + " last_seen_at FROM saga_message_store_consumer WHERE last_seen_at >= ? ORDER BY consumer_id")) {
                statement.setObject(1, utc(since));
                List<ConsumerHeartbeat> beats = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        beats.add(new ConsumerHeartbeat(resultSet.getString(1), resultSet.getString(2),
                                instant(resultSet, 3), instant(resultSet, 4)));
                    }
                }
                return beats;
            }
        });
    }

    // ----- helpers ------------------------------------------------------------------------------------------

    private Object decode(String payloadType, String json) throws ClassNotFoundException {
        if (json == null) {
            throw new IllegalStateException("message has no payload");
        }
        return PayloadCodec.decode(payloadType, json, payloadClassLoader);
    }

    /** Reads rows; with {@code rawPayload} the payload JSON text is carried as the payload, otherwise none. */
    private static List<StoredMessage> readAll(PreparedStatement statement, boolean rawPayload) throws SQLException {
        List<StoredMessage> rows = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(row(resultSet, rawPayload));
            }
        }
        return rows;
    }

    private static StoredMessage row(ResultSet r, boolean rawPayload) throws SQLException {
        String commandId = r.getString("command_id");
        String lastFailure = r.getString("last_failure");
        return new StoredMessage(r.getLong("position"), r.getString("message_id"),
                MessageKind.valueOf(r.getString("kind")), r.getString("payload_type"),
                rawPayload ? r.getString("payload") : null,
                commandId == null ? null : PayloadCodec.decodeCommandId(commandId), r.getString("ordering_key"),
                r.getString("source_saga_type"), r.getString("source_saga_id"), instant(r, "recorded_at"),
                MessageStatus.valueOf(r.getString("status")), r.getInt("attempts"), instant(r, "next_attempt_at"),
                lastFailure == null ? null : PayloadCodec.decodeFailure(lastFailure), r.getString("leased_by"),
                instant(r, "leased_until"), instant(r, "consumed_at"), r.getString("set_aside_reason"),
                r.getString("external_ref"));
    }

    private static String qualified(String alias) {
        return List.of(COLUMNS.split(",\\s*")).stream().map(c -> alias + "." + c.strip())
                .collect(Collectors.joining(", "));
    }

    @FunctionalInterface
    private interface ParameterSetter {
        void set(PreparedStatement statement) throws SQLException;
    }

    /** {@code UPDATE ... SET <assignments> WHERE message_id = ? AND leased_by = ? AND status = 'UNCONSUMED'}. */
    private static void holderUpdate(Connection connection, String consumerId, String messageId, String assignments,
            ParameterSetter setter) throws SQLException {
        int assignmentParameters = (int) assignments.chars().filter(c -> c == '?').count();
        try (PreparedStatement statement = connection.prepareStatement("UPDATE saga_message_store SET " + assignments
                + " WHERE message_id = ? AND leased_by = ? AND status = 'UNCONSUMED'")) {
            setter.set(statement);
            statement.setString(assignmentParameters + 1, messageId);
            statement.setString(assignmentParameters + 2, consumerId);
            if (statement.executeUpdate() != 1) {
                throw new LeaseLostException(messageId, consumerId);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T inOwnTransaction(String action, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                } catch (SQLException ignored) {
                    // The connection is closed next; a broken one is discarded by the pool.
                }
            }
        } catch (SQLException e) {
            throw new UncheckedSqlException("could not " + action, e);
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        for (SQLException current = e; current != null; current = current.getNextException()) {
            if (UNIQUE_VIOLATION.equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static String duplicatedId(List<NewMessage> entries, SQLException e) {
        String text = e.getMessage() + (e.getNextException() == null ? "" : e.getNextException().getMessage());
        return entries.stream().map(NewMessage::messageId).filter(text::contains).findFirst()
                .orElse(entries.get(0).messageId());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet r, int column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
