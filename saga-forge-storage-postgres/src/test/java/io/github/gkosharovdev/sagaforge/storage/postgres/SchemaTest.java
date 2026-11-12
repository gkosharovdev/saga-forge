package io.github.gkosharovdev.sagaforge.storage.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code schema.sql} against a real PostgreSQL (contracts/postgres-schema.md): it applies cleanly, its
 * {@code CHECK} constraints back {@code SagaInstance}'s invariants (FR-011, defense in depth), and deleting an
 * instance row cascades to every dependent table.
 */
@Tag("container")
class SchemaTest {

    private static final String CHECK_VIOLATION = "23514";

    private static final String INSERT_INSTANCE = "INSERT INTO saga_instance (saga_id, saga_type, state, status, "
            + "created_at, last_modified_at, steps, outcome, failure_step_name, failure_cause_type, "
            + "failure_message, failure_failed_at, failure_classification, failure_attempts) "
            + "VALUES (?, 'com.example.OrderSaga', '{}'::jsonb, ?, now(), now(), '{}'::jsonb, ?, ?, ?, NULL, ?, ?, ?)";

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
    }

    @Test
    @DisplayName("the schema applies cleanly: six tables and the association lookup index exist")
    void schemaApplies() {
        List<Map<String, Object>> tables = PostgresTestSupport.queryRows(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "ORDER BY table_name");
        List<Map<String, Object>> index = PostgresTestSupport.queryRows(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", "idx_saga_association_lookup");

        assertThat(tables).extracting(row -> row.get("table_name")).containsExactly(
                "saga_association", "saga_context", "saga_instance", "saga_lifecycle_history", "saga_message_store",
                "saga_message_store_consumer");
        assertThat(index).singleElement()
                .extracting(row -> (String) row.get("indexdef"))
                .asString()
                .contains("saga_association")
                .contains("(association_property, association_value)");
    }

    @Test
    @DisplayName("valid COMPLETED, FAILED and RUNNING rows are accepted")
    void validRowsAccepted() throws SQLException {
        insertInstance("s-running", "RUNNING", null, null);
        insertInstance("s-completed", "COMPLETED", "completeOrder", null);
        insertInstance("s-failed", "FAILED", null, "processPayment");

        assertThat(PostgresTestSupport.queryRows("SELECT saga_id FROM saga_instance")).hasSize(3);
    }

    @Test
    @DisplayName("CHECK outcome_iff_completed rejects COMPLETED without outcome, and outcome without COMPLETED")
    void completedRequiresOutcome() {
        assertThatThrownBy(() -> insertInstance("s-1", "COMPLETED", null, null))
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION))
                .hasMessageContaining("outcome_iff_completed");
        assertThatThrownBy(() -> insertInstance("s-2", "RUNNING", "completeOrder", null))
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION))
                .hasMessageContaining("outcome_iff_completed");
    }

    @Test
    @DisplayName("CHECK failure_iff_failed rejects FAILED without failure_step_name, and a failure without FAILED")
    void failedRequiresFailureStep() {
        assertThatThrownBy(() -> insertInstance("s-1", "FAILED", null, null))
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION))
                .hasMessageContaining("failure_iff_failed");
        assertThatThrownBy(() -> insertInstance("s-2", "RUNNING", null, "processPayment"))
                .isInstanceOfSatisfying(SQLException.class,
                        e -> assertThat(e.getSQLState()).isEqualTo(CHECK_VIOLATION))
                .hasMessageContaining("failure_iff_failed");
    }

    @Test
    @DisplayName("deleting a saga_instance row cascades to association, context and lifecycle-history rows")
    void deleteCascades() throws SQLException {
        insertInstance("s-1", "RUNNING", null, null);
        insertInstance("s-2", "RUNNING", null, null);
        for (String sagaId : List.of("s-1", "s-2")) {
            execute("INSERT INTO saga_association (saga_id, association_property, association_value) "
                    + "VALUES (?, 'orderId', 'o-1')", sagaId);
            execute("INSERT INTO saga_context (saga_id, values, last_modified_at) "
                    + "VALUES (?, '{\"total\": 42}'::jsonb, now())", sagaId);
            execute("INSERT INTO saga_lifecycle_history (saga_id, sequence_number, kind, occurred_at) "
                    + "VALUES (?, 0, 'CREATED', now())", sagaId);
        }

        execute("DELETE FROM saga_instance WHERE saga_id = ?", "s-1");

        for (String table : List.of("saga_association", "saga_context", "saga_lifecycle_history")) {
            assertThat(PostgresTestSupport.queryRows("SELECT saga_id FROM " + table + " ORDER BY saga_id"))
                    .as(table)
                    .extracting(row -> row.get("saga_id"))
                    .containsExactly("s-2");
        }
        assertThat(PostgresTestSupport.queryRows("SELECT values FROM saga_context"))
                .singleElement()
                .extracting(row -> JsonCodec.decodeObject("values", (String) row.get("values")))
                .isEqualTo(Map.of("total", 42));
        assertThat(PostgresTestSupport.queryRows(
                "SELECT touched_aggregates FROM saga_lifecycle_history WHERE saga_id = ?", "s-2"))
                .singleElement()
                .extracting(row -> JsonCodec.decode("touched_aggregates", (String) row.get("touched_aggregates")))
                .isEqualTo(List.of());
    }

    private static void insertInstance(String sagaId, String status, String outcome, String failureStepName)
            throws SQLException {
        boolean hasFailure = failureStepName != null;
        execute(INSERT_INSTANCE, sagaId, status, outcome, failureStepName,
                hasFailure ? IllegalStateException.class.getName() : null,
                hasFailure ? OffsetDateTime.now() : null,
                hasFailure ? "UNCLASSIFIED" : null,
                hasFailure ? 1 : null);
    }

    @Test
    @DisplayName("005: a FAILED row needs a classification and an attempt count")
    void failureNeedsClassificationAndAttempts() {
        assertThatThrownBy(() -> execute("INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at,"
                + " last_modified_at, steps, failure_step_name, failure_cause_type, failure_failed_at) VALUES ('s-1',"
                + " 'com.example.OrderSaga', '{}'::jsonb, 'FAILED', now(), now(), '{}'::jsonb, 'processPayment',"
                + " 'java.lang.IllegalStateException', now())"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    @Test
    @DisplayName("005: pending_retry is allowed only while CREATED or RUNNING")
    void pendingRetryOnlyWhileActive() throws SQLException {
        insertInstance("s-1", "RUNNING", null, null);
        execute("UPDATE saga_instance SET pending_retry = '{}'::jsonb WHERE saga_id = ?", "s-1");

        assertThatThrownBy(() -> execute("UPDATE saga_instance SET pending_retry = '{}'::jsonb, status = 'COMPLETED',"
                + " outcome = 'completeOrder' WHERE saga_id = ?", "s-1"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(CHECK_VIOLATION));
    }

    @Test
    @DisplayName("005: schema-upgrade-005.sql upgrades a pre-005 schema; old FAILED rows become UNCLASSIFIED, 1")
    void upgradeScriptApplies() throws SQLException {
        try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS pre005 CASCADE");
            statement.execute("CREATE SCHEMA pre005");
            statement.execute("SET search_path TO pre005");
            try {
                for (String sql : PostgresTestSupport.statements("schema-before-005.sql")) {
                    statement.execute(sql);
                }
                statement.execute("INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at,"
                        + " last_modified_at, steps, failure_step_name, failure_cause_type, failure_failed_at)"
                        + " VALUES ('old', 'com.example.OrderSaga', '{}'::jsonb, 'FAILED', now(), now(), '{}'::jsonb,"
                        + " 'processPayment', 'java.lang.IllegalStateException', now())");
                for (String sql : PostgresTestSupport.statements("schema-upgrade-005.sql")) {
                    statement.execute(sql);
                }
                try (ResultSet row = statement.executeQuery(
                        "SELECT failure_classification, failure_attempts, pending_retry FROM saga_instance")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("UNCLASSIFIED");
                    assertThat(row.getInt(2)).isEqualTo(1);
                    assertThat(row.getString(3)).isNull();
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA pre005 CASCADE");
            }
        }
    }

    @Test
    @DisplayName("006: CHECK trigger_iff_compensation requires a trigger exactly while compensating or compensated")
    void triggerIffCompensation() throws SQLException {
        insertInstance("s-1", "RUNNING", null, null);
        execute("UPDATE saga_instance SET status = 'COMPENSATING', compensation_trigger = '{}'::jsonb "
                + "WHERE saga_id = ?", "s-1");

        assertThatThrownBy(() -> execute("UPDATE saga_instance SET compensation_trigger = NULL WHERE saga_id = ?",
                "s-1"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(CHECK_VIOLATION))
                .hasMessageContaining("trigger_iff_compensation");
        insertInstance("s-2", "RUNNING", null, null);
        assertThatThrownBy(() -> execute("UPDATE saga_instance SET compensation_trigger = '{}'::jsonb "
                + "WHERE saga_id = ?", "s-2"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("trigger_iff_compensation");
    }

    @Test
    @DisplayName("006: schema-upgrade-006.sql backfills sequences from history, then by name, and is idempotent")
    void upgradeScript006Applies() throws SQLException {
        try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS pre006 CASCADE");
            statement.execute("CREATE SCHEMA pre006");
            statement.execute("SET search_path TO pre006");
            try {
                for (String sql : PostgresTestSupport.statements("schema-before-006.sql")) {
                    statement.execute(sql);
                }
                statement.execute("INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at,"
                        + " last_modified_at, steps) VALUES ('old', 'com.example.OrderSaga', '{}'::jsonb, 'RUNNING',"
                        + " now(), now(), '{\"a\": {\"status\": \"COMPLETED\", \"touchedAggregates\": []},"
                        + " \"b\": {\"status\": \"COMPLETED\", \"touchedAggregates\": []},"
                        + " \"c\": {\"status\": \"COMPLETED\", \"touchedAggregates\": []},"
                        + " \"d\": {\"status\": \"NOT_RUN\", \"touchedAggregates\": []}}'::jsonb)");
                statement.execute("INSERT INTO saga_lifecycle_history (saga_id, sequence_number, kind, step_name,"
                        + " occurred_at) VALUES ('old', 1, 'CREATED', NULL, now()),"
                        + " ('old', 2, 'STEP_COMPLETED', 'b', now()), ('old', 3, 'STEP_COMPLETED', 'a', now())");
                for (int run = 0; run < 2; run++) {
                    for (String sql : PostgresTestSupport.statements("schema-upgrade-006.sql")) {
                        statement.execute(sql);
                    }
                    try (ResultSet row = statement.executeQuery("SELECT steps -> 'a' ->> 'sequence',"
                            + " steps -> 'b' ->> 'sequence', steps -> 'c' ->> 'sequence', steps -> 'd' ->> 'sequence',"
                            + " compensation_trigger FROM saga_instance")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getString(1)).as("a, second in history").isEqualTo("2");
                        assertThat(row.getString(2)).as("b, first in history").isEqualTo("1");
                        assertThat(row.getString(3)).as("c, no history row").isEqualTo("3");
                        assertThat(row.getString(4)).as("d, not run").isNull();
                        assertThat(row.getString(5)).isNull();
                    }
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA pre006 CASCADE");
            }
        }
    }

    @Test
    @DisplayName("007: the message store has its columns, checks and partial indexes")
    void messageLogShape() throws SQLException {
        assertThat(PostgresTestSupport.queryRows("SELECT indexname FROM pg_indexes WHERE tablename = ?"
                + " ORDER BY indexname", "saga_message_store")).extracting(row -> row.get("indexname")).contains(
                        "saga_message_store_consumed", "saga_message_store_due", "saga_message_store_head",
                        "saga_message_store_source");
        execute("INSERT INTO saga_message_store (message_id, kind, payload_type, payload, recorded_at, status,"
                + " next_attempt_at) VALUES ('m-1', 'EVENT', 'T', '{}'::jsonb, now(), 'UNCONSUMED', now())");
        assertThatThrownBy(() -> execute("INSERT INTO saga_message_store (message_id, kind, payload_type,"
                + " recorded_at, status, next_attempt_at) VALUES ('m-2', 'COMMAND', 'T', now(), 'UNCONSUMED', now())"))
                .as("a command needs a command id").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO saga_message_store (message_id, kind, payload_type,"
                + " recorded_at, status, next_attempt_at) VALUES ('m-3', 'EVENT', 'T', now(), 'CONSUMED', now())"))
                .as("consumed needs consumed_at").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO saga_message_store (message_id, kind, payload_type,"
                + " recorded_at, status, next_attempt_at) VALUES ('m-4', 'EVENT', 'T', now(), 'SET_ASIDE', now())"))
                .as("set aside needs a reason").isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO saga_message_store (message_id, kind, payload_type,"
                + " recorded_at, status, next_attempt_at) VALUES ('m-1', 'EVENT', 'T', now(), 'UNCONSUMED', now())"))
                .as("message ids are unique").isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("007: schema-upgrade-007.sql adds the message store to a 006 database and is idempotent")
    void upgradeScript007Applies() throws SQLException {
        try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS pre007 CASCADE");
            statement.execute("CREATE SCHEMA pre007");
            statement.execute("SET search_path TO pre007");
            try {
                for (String sql : PostgresTestSupport.statements("schema-before-006.sql")) {
                    statement.execute(sql);
                }
                for (String sql : PostgresTestSupport.statements("schema-upgrade-006.sql")) {
                    statement.execute(sql);
                }
                for (int run = 0; run < 2; run++) {
                    for (String sql : PostgresTestSupport.statements("schema-upgrade-007.sql")) {
                        statement.execute(sql);
                    }
                }
                try (ResultSet tables = statement.executeQuery("SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'pre007' AND table_name LIKE 'saga_message_store%'")) {
                    assertThat(tables.next()).isTrue();
                    assertThat(tables.getInt(1)).isEqualTo(2);
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA pre007 CASCADE");
            }
        }
    }

    @Test
    @DisplayName("010: schema-upgrade-010.sql adds trace_context to a 007 message store and is idempotent")
    void upgradeScript010Applies() throws SQLException {
        try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS pre010 CASCADE");
            statement.execute("CREATE SCHEMA pre010");
            statement.execute("SET search_path TO pre010");
            try {
                for (String script : new String[] {"schema-before-006.sql", "schema-upgrade-006.sql",
                        "schema-upgrade-007.sql", "schema-upgrade-010.sql", "schema-upgrade-010.sql"}) {
                    for (String sql : PostgresTestSupport.statements(script)) {
                        statement.execute(sql);
                    }
                }
                try (ResultSet column = statement.executeQuery("SELECT data_type FROM information_schema.columns"
                        + " WHERE table_schema = 'pre010' AND table_name = 'saga_message_store'"
                        + " AND column_name = 'trace_context'")) {
                    assertThat(column.next()).isTrue();
                    assertThat(column.getString(1)).isEqualTo("jsonb");
                }
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA pre010 CASCADE");
            }
        }
    }

    private static void execute(String sql, Object... params) throws SQLException {
        try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }
}
