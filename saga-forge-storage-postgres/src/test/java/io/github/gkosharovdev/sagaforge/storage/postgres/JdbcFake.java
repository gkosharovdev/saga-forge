package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;

/**
 * A statement-recording JDBC fake over {@code java.lang.reflect.Proxy}, JDK only (specs/004-saga-engine tasks.md T060,
 * research.md §15). It proves the provider's call sequence and SQL text, not database semantics.
 *
 * <ul>
 *   <li>{@link #dataSource()} hands out a new fake {@link Connection} per {@code getConnection()}; each is recorded as
 *       a {@link ConnectionLog}: its lifecycle calls ({@code setAutoCommit(false)}, {@code commit}, {@code rollback},
 *       {@code close}) and every executed {@link Executed statement} with its bound parameters, one map per batch
 *       row.</li>
 *   <li>Queries return the rows {@linkplain #script scripted} for the first registered SQL fragment the statement
 *       contains, else no row. A row is a column-label-to-value map in column order, so {@code getLong(1)} reads its
 *       first value.</li>
 *   <li>{@link #failOn(String, SQLException)} makes the next statement containing a fragment throw at execution;
 *       {@link #failNextCommit(SQLException)} the next {@code commit()}.</li>
 *   <li>Any JDBC method the provider is not expected to call throws {@link UnsupportedOperationException} naming
 *       it.</li>
 * </ul>
 *
 * <p>Never references {@code PostgresTestSupport}: it starts no container. Thread-safe enough for one test thread.</p>
 */
final class JdbcFake {

    /** One executed statement: its SQL and its bound parameters, one map (index to value) per batch row. */
    record Executed(String sql, List<Map<Integer, Object>> rows) {

        Executed {
            rows = List.copyOf(rows.stream().map(row -> Collections.unmodifiableMap(new HashMap<>(row))).toList());
        }

        /** The single row of a non-batch statement. */
        Map<Integer, Object> parameters() {
            if (rows.size() != 1) {
                throw new IllegalStateException(sql + " ran with " + rows.size() + " parameter rows");
            }
            return rows.get(0);
        }
    }

    /** What happened on one connection, in order. */
    static final class ConnectionLog {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<Executed> statements = new CopyOnWriteArrayList<>();
        private boolean autoCommit = true;
        private boolean closed;

        /** Lifecycle calls: {@code setAutoCommit(true|false)}, {@code commit}, {@code rollback}, {@code close}. */
        List<String> calls() {
            return List.copyOf(calls);
        }

        List<Executed> statements() {
            return List.copyOf(statements);
        }

        /** The executed statements whose SQL contains {@code fragment}. */
        List<Executed> statementsContaining(String fragment) {
            return statements.stream().filter(statement -> statement.sql().contains(fragment)).toList();
        }

        boolean isClosed() {
            return closed;
        }
    }

    private record Script(String fragment, List<Map<String, Object>> rows) {
    }

    private final List<Script> scripts = new CopyOnWriteArrayList<>();
    private final Map<String, SQLException> failures = new LinkedHashMap<>();
    private final List<ConnectionLog> connections = new CopyOnWriteArrayList<>();
    private volatile SQLException nextCommitFailure;

    /** Queries whose SQL contains {@code fragment} return {@code rows}; the first matching script wins. */
    JdbcFake script(String fragment, List<Map<String, Object>> rows) {
        scripts.add(new Script(fragment, List.copyOf(rows)));
        return this;
    }

    /** The next statement whose SQL contains {@code fragment} throws {@code failure} when executed. */
    JdbcFake failOn(String fragment, SQLException failure) {
        synchronized (failures) {
            failures.put(fragment, failure);
        }
        return this;
    }

    /** The next {@code commit()} on any connection is recorded, then throws {@code failure}. */
    JdbcFake failNextCommit(SQLException failure) {
        nextCommitFailure = failure;
        return this;
    }

    /** A {@link DataSource} whose every connection is recorded here. */
    DataSource dataSource() {
        return proxy(DataSource.class, (method, args) -> switch (method.getName()) {
            case "getConnection" -> newConnection();
            default -> unsupported(DataSource.class, method);
        });
    }

    /** Every connection handed out, in order. */
    List<ConnectionLog> connections() {
        return List.copyOf(connections);
    }

    /** The only connection handed out. */
    ConnectionLog onlyConnection() {
        if (connections.size() != 1) {
            throw new IllegalStateException(connections.size() + " connections were handed out");
        }
        return connections.get(0);
    }

    // ---- row helpers

    /** The {@code saga_instance} columns plus the {@code associations} JSON of {@code instance}, as a query row. */
    static Map<String, Object> instanceRow(SagaInstance instance) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("saga_id", instance.sagaId());
        row.put("saga_type", instance.sagaType());
        row.put("state", SagaInstanceRows.encodeState(instance.state()));
        row.put("status", instance.status().name());
        row.put("created_at", OffsetDateTime.ofInstant(instance.createdAt(), ZoneOffset.UTC));
        row.put("last_modified_at", OffsetDateTime.ofInstant(instance.lastModifiedAt(), ZoneOffset.UTC));
        row.put("steps", SagaInstanceRows.encodeSteps(instance.steps()));
        row.put("outcome", instance.outcome());
        row.put("failure_step_name", instance.failure() == null ? null : instance.failure().stepName());
        row.put("failure_cause_type", instance.failure() == null ? null : instance.failure().causeType());
        row.put("failure_message", instance.failure() == null ? null : instance.failure().message());
        row.put("failure_failed_at", instance.failure() == null ? null
                : OffsetDateTime.ofInstant(instance.failure().failedAt(), ZoneOffset.UTC));
        row.put("failure_classification", instance.failure() == null ? null
                : instance.failure().classification().name());
        row.put("failure_attempts", instance.failure() == null ? null : instance.failure().attempts());
        row.put("pending_retry", SagaInstanceRows.encodePendingRetry(instance.pendingRetry()));
        row.put("compensation_trigger", SagaInstanceRows.encodeTrigger(SagaInstanceRows.TRIGGER_FIELD,
                instance.compensationTrigger()));
        row.put("associations",
                JsonCodec.encodeObject("associations", new LinkedHashMap<String, Object>(instance.associations())));
        return row;
    }

    /** A one-column row, for {@code NEXT_SEQUENCE_NUMBER} and the upsert's {@code inserted} flag. */
    static Map<String, Object> single(String column, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(column, value);
        return row;
    }

    // ---- proxies

    private Connection newConnection() {
        ConnectionLog log = new ConnectionLog();
        connections.add(log);
        return proxy(Connection.class, (method, args) -> switch (method.getName()) {
            case "prepareStatement" -> newStatement(log, (String) args[0]);
            case "getAutoCommit" -> log.autoCommit;
            case "setAutoCommit" -> {
                log.autoCommit = (Boolean) args[0];
                log.calls.add("setAutoCommit(" + args[0] + ")");
                yield null;
            }
            case "commit" -> {
                log.calls.add("commit");
                SQLException failure = nextCommitFailure;
                nextCommitFailure = null;
                if (failure != null) {
                    throw failure;
                }
                yield null;
            }
            case "rollback" -> {
                log.calls.add("rollback");
                yield null;
            }
            case "close" -> {
                log.closed = true;
                log.calls.add("close");
                yield null;
            }
            case "isClosed" -> log.closed;
            default -> unsupported(Connection.class, method);
        });
    }

    private PreparedStatement newStatement(ConnectionLog log, String sql) {
        Map<Integer, Object> current = new HashMap<>();
        List<Map<Integer, Object>> batch = new ArrayList<>();
        return proxy(PreparedStatement.class, (method, args) -> switch (method.getName()) {
            case "setString", "setLong", "setInt", "setObject", "setBoolean" -> {
                current.put((Integer) args[0], args[1]);
                yield null;
            }
            case "setNull" -> {
                current.put((Integer) args[0], null);
                yield null;
            }
            case "addBatch" -> {
                batch.add(new HashMap<>(current));
                current.clear();
                yield null;
            }
            case "executeBatch" -> {
                execute(log, sql, batch);
                yield new int[batch.size()];
            }
            case "executeUpdate" -> {
                execute(log, sql, List.of(current));
                yield 1;
            }
            case "executeQuery" -> {
                execute(log, sql, List.of(current));
                yield newResultSet(rowsFor(sql));
            }
            case "close" -> null;
            default -> unsupported(PreparedStatement.class, method);
        });
    }

    private void execute(ConnectionLog log, String sql, List<Map<Integer, Object>> rows) throws SQLException {
        SQLException failure = takeFailure(sql);
        if (failure != null) {
            throw failure;
        }
        log.statements.add(new Executed(sql, rows));
    }

    private SQLException takeFailure(String sql) {
        synchronized (failures) {
            for (Map.Entry<String, SQLException> entry : failures.entrySet()) {
                if (sql.contains(entry.getKey())) {
                    failures.remove(entry.getKey());
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    private List<Map<String, Object>> rowsFor(String sql) {
        return scripts.stream().filter(script -> sql.contains(script.fragment())).findFirst()
                .map(Script::rows).orElse(List.of());
    }

    private static ResultSet newResultSet(List<Map<String, Object>> rows) {
        int[] position = {-1};
        return proxy(ResultSet.class, (method, args) -> switch (method.getName()) {
            case "next" -> ++position[0] < rows.size();
            case "getString" -> (String) value(rows.get(position[0]), args[0]);
            case "getLong" -> ((Number) value(rows.get(position[0]), args[0])).longValue();
            case "getBoolean" -> (Boolean) value(rows.get(position[0]), args[0]);
            case "getObject" -> value(rows.get(position[0]), args[0]);
            case "close" -> null;
            default -> unsupported(ResultSet.class, method);
        });
    }

    private static Object value(Map<String, Object> row, Object column) {
        if (column instanceof Integer index) {
            return new ArrayList<>(row.values()).get(index - 1);
        }
        if (!row.containsKey(column)) {
            throw new IllegalStateException("Scripted row has no column " + column + ": " + row.keySet());
        }
        return row.get(column);
    }

    // ---- proxy plumbing

    @FunctionalInterface
    private interface Handler {

        Object handle(Method method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        InvocationHandler invocation = (proxy, method, args) -> switch (method.getName()) {
            case "toString" -> "JdbcFake " + type.getSimpleName() + "@" + System.identityHashCode(proxy);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "unwrap", "isWrapperFor" -> unsupported(type, method);
            default -> handler.handle(method, args);
        };
        return type.cast(Proxy.newProxyInstance(JdbcFake.class.getClassLoader(), new Class<?>[] {type}, invocation));
    }

    private static Object unsupported(Class<?> type, Method method) {
        throw new UnsupportedOperationException("JdbcFake does not support " + type.getSimpleName() + "."
                + method.getName());
    }
}
