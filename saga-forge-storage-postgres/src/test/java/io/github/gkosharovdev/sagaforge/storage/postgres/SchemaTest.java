package io.github.gkosharovdev.sagaforge.storage.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
class SchemaTest {

    private static final String CHECK_VIOLATION = "23514";

    private static final String INSERT_INSTANCE = "INSERT INTO saga_instance (saga_id, saga_type, state, status, "
            + "created_at, last_modified_at, steps, outcome, failure_step_name, failure_cause_type, "
            + "failure_message, failure_failed_at) "
            + "VALUES (?, 'com.example.OrderSaga', '{}'::jsonb, ?, now(), now(), '{}'::jsonb, ?, ?, ?, NULL, ?)";

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
    }

    @Test
    @DisplayName("the schema applies cleanly: four tables and the association lookup index exist")
    void schemaApplies() {
        List<Map<String, Object>> tables = PostgresTestSupport.queryRows(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "ORDER BY table_name");
        List<Map<String, Object>> index = PostgresTestSupport.queryRows(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", "idx_saga_association_lookup");

        assertThat(tables).extracting(row -> row.get("table_name")).containsExactly(
                "saga_association", "saga_context", "saga_instance", "saga_lifecycle_history");
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
                hasFailure ? OffsetDateTime.now() : null);
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
