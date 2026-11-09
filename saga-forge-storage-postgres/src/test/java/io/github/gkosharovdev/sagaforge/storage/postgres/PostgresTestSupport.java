package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.util.PGobject;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL test fixture: one {@code postgres:16-alpine} container per test JVM, with
 * {@code schema.sql} applied once.
 *
 * <p>The container is started in a static initializer rather than through {@code @Testcontainers} /
 * {@code @Container}, because jqwik properties do not run JUnit Jupiter extensions. Every test class that
 * touches the database shares it and calls {@link #reset()} before each test.</p>
 *
 * <p>Every class that uses it must carry the JUnit tag {@code container} ({@link org.junit.jupiter.api.Tag}),
 * and it runs only with {@code -Pcontainer-tests}.</p>
 */
final class PostgresTestSupport {

    static final String SCHEMA_RESOURCE = "/io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql";

    private static final String FAULT_FUNCTION = "saga_forge_test_fault";
    private static final String FAULT_TRIGGER_PREFIX = "saga_forge_test_fault_";
    private static final Set<String> TABLES =
            Set.of("saga_instance", "saga_association", "saga_context", "saga_lifecycle_history");
    private static final Set<String> OPERATIONS = Set.of("INSERT", "UPDATE", "DELETE");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop, "postgres-test-container-stop"));
        applySchema();
    }

    private PostgresTestSupport() {
    }

    /**
     * @return a new {@link DataSource} on the shared container on every call, so a test can simulate a
     *         restart by opening a new provider on a new {@code DataSource}
     */
    static DataSource newDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    /**
     * Removes every row ({@code TRUNCATE saga_instance CASCADE} also empties the three tables referencing it)
     * and drops every fault trigger installed by {@link #failOn(String, String)}.
     */
    static void reset() {
        try (Connection connection = newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            List<String> drops = new ArrayList<>();
            try (ResultSet triggers = statement.executeQuery(
                    "SELECT t.tgname, c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                            + "WHERE NOT t.tgisinternal AND t.tgname LIKE '" + FAULT_TRIGGER_PREFIX + "%'")) {
                while (triggers.next()) {
                    drops.add("DROP TRIGGER " + triggers.getString(1) + " ON " + triggers.getString(2));
                }
            }
            for (String drop : drops) {
                statement.execute(drop);
            }
            statement.execute("TRUNCATE saga_instance CASCADE");
        } catch (SQLException e) {
            throw new IllegalStateException("could not reset the test database", e);
        }
    }

    /**
     * Installs a {@code BEFORE <operation>} row trigger on {@code table} whose function raises an exception:
     * a real database failure for the next matching write, not a mock. {@link #reset()} drops it.
     *
     * @param table     one of the four schema tables
     * @param operation {@code INSERT}, {@code UPDATE}, or {@code DELETE}
     */
    static void failOn(String table, String operation) {
        String op = operation.toUpperCase(Locale.ROOT);
        if (!TABLES.contains(table) || !OPERATIONS.contains(op)) {
            throw new IllegalArgumentException("unsupported fault target " + table + " " + operation);
        }
        try (Connection connection = newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION " + FAULT_FUNCTION + "() RETURNS trigger "
                    + "LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'injected test fault: % on %', TG_OP, TG_TABLE_NAME; "
                    + "END $$");
            String trigger = FAULT_TRIGGER_PREFIX + table + "_" + op.toLowerCase(Locale.ROOT);
            statement.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            statement.execute("CREATE TRIGGER " + trigger + " BEFORE " + op + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + FAULT_FUNCTION + "()");
        } catch (SQLException e) {
            throw new IllegalStateException("could not install fault trigger on " + table, e);
        }
    }

    /**
     * Runs {@code sql} with positional {@code params} and returns every row as a column-label-keyed map in
     * column order, for direct SQL assertions (FR-012). {@code JSONB} columns come back as their JSON text,
     * {@code TIMESTAMPTZ} columns as {@link java.time.Instant}, everything else as the driver's
     * {@code getObject}; SQL {@code NULL} is a {@code null} map value.
     */
    static List<Map<String, Object>> queryRows(String sql, Object... params) {
        try (Connection connection = newDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData meta = resultSet.getMetaData();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= meta.getColumnCount(); column++) {
                        row.put(meta.getColumnLabel(column), readColumn(resultSet, meta, column));
                    }
                    rows.add(Collections.unmodifiableMap(row));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private static Object readColumn(ResultSet resultSet, ResultSetMetaData meta, int column)
            throws SQLException {
        if ("timestamptz".equals(meta.getColumnTypeName(column))) {
            OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }
        Object value = resultSet.getObject(column);
        return value instanceof PGobject pg ? pg.getValue() : value;
    }

    /**
     * Splits {@code schema.sql} into statements on {@code ;} (after dropping {@code --} comment lines) and
     * runs them in order.
     */
    private static void applySchema() {
        try (Connection connection = newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : schemaStatements()) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not apply " + SCHEMA_RESOURCE, e);
        }
    }

    static List<String> schemaStatements() {
        String script;
        try (InputStream in = PostgresTestSupport.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is not on the classpath");
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + SCHEMA_RESOURCE, e);
        }
        String withoutComments = script.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(sql -> !sql.isEmpty())
                .toList();
    }
}
