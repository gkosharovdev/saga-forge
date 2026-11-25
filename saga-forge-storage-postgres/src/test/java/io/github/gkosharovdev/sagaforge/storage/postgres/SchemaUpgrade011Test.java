package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code schema-upgrade-011.sql} on a 0.1.0 database (specs/011-saga-outcome-events research §6,
 * FR-022, Q14): a {@code REQUIRES_INTERVENTION} instance becomes {@code COMPENSATION_FAILED} with
 * every other column unchanged, its {@code REQUIRES_INTERVENTION} history row goes and its {@code
 * COMPENSATION_FAILED} row stays, and a second run changes nothing.
 */
@Tag("container")
class SchemaUpgrade011Test {

  private static final String SCHEMA = "pre011";

  private static final String[] RELEASE_0_1_0 = {
    "schema-before-006.sql",
    "schema-upgrade-006.sql",
    "schema-upgrade-007.sql",
    "schema-upgrade-010.sql"
  };

  private static final String OTHER_COLUMNS =
      "SELECT (to_jsonb(i) - 'status')::text FROM saga_instance i" + " WHERE saga_id = 'stuck'";

  @Test
  @DisplayName(
      "REQUIRES_INTERVENTION becomes COMPENSATION_FAILED, its extra history row goes, and a rerun is a no-op")
  void upgradesARequiresInterventionInstance() throws SQLException {
    try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      statement.execute("CREATE SCHEMA " + SCHEMA);
      statement.execute("SET search_path TO " + SCHEMA);
      try {
        for (String script : RELEASE_0_1_0) {
          run(statement, script);
        }
        insertStuckInstance(statement);
        String otherColumnsBefore = single(statement, OTHER_COLUMNS);

        run(statement, "schema-upgrade-011.sql");

        assertThat(single(statement, "SELECT status FROM saga_instance WHERE saga_id = 'stuck'"))
            .isEqualTo("COMPENSATION_FAILED");
        assertThat(single(statement, OTHER_COLUMNS)).isEqualTo(otherColumnsBefore);
        assertThat(historyKinds(statement))
            .containsExactly(
                "CREATED", "STEP_COMPLETED", "COMPENSATION_STARTED", "COMPENSATION_FAILED");
        assertThat(
                single(
                    statement,
                    "SELECT count(*) FROM saga_instance"
                        + " WHERE status = 'REQUIRES_INTERVENTION'"))
            .isEqualTo("0");
        String afterFirstRun = single(statement, "SELECT to_jsonb(i)::text FROM saga_instance i");

        run(statement, "schema-upgrade-011.sql");

        assertThat(single(statement, "SELECT to_jsonb(i)::text FROM saga_instance i"))
            .isEqualTo(afterFirstRun);
        assertThat(historyKinds(statement))
            .containsExactly(
                "CREATED", "STEP_COMPLETED", "COMPENSATION_STARTED", "COMPENSATION_FAILED");
      } finally {
        statement.execute("SET search_path TO public");
        statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
      }
    }
  }

  private static void insertStuckInstance(Statement statement) throws SQLException {
    String trigger =
        "'{\"kind\": \"FAILURE_OUTCOME\", \"stepName\": \"reject\","
            + " \"eventType\": \"com.example.Rejected\", \"at\": \"2026-01-01T00:00:00Z\"}'::jsonb";
    statement.execute(
        "INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at,"
            + " last_modified_at, steps, compensation_trigger) VALUES ('stuck', 'com.example.OrderSaga',"
            + " '{}'::jsonb, 'REQUIRES_INTERVENTION', '2026-01-01T00:00:00Z', '2026-01-01T00:00:05Z',"
            + " '{\"pay\": {\"status\": \"COMPLETED\", \"touchedAggregates\": [], \"sequence\": 1,"
            + " \"compensation\": {\"status\": \"FAILED\"}}}'::jsonb, "
            + trigger
            + ")");
    statement.execute(
        "INSERT INTO saga_lifecycle_history (saga_id, sequence_number, kind, step_name,"
            + " occurred_at) VALUES ('stuck', 1, 'CREATED', NULL, '2026-01-01T00:00:00Z'),"
            + " ('stuck', 2, 'STEP_COMPLETED', 'pay', '2026-01-01T00:00:01Z'),"
            + " ('stuck', 3, 'COMPENSATION_STARTED', NULL, '2026-01-01T00:00:02Z'),"
            + " ('stuck', 4, 'COMPENSATION_FAILED', 'pay', '2026-01-01T00:00:05Z'),"
            + " ('stuck', 5, 'REQUIRES_INTERVENTION', NULL, '2026-01-01T00:00:05Z')");
  }

  private static List<String> historyKinds(Statement statement) throws SQLException {
    List<String> kinds = new ArrayList<>();
    try (ResultSet rows =
        statement.executeQuery(
            "SELECT kind FROM saga_lifecycle_history"
                + " WHERE saga_id = 'stuck' ORDER BY sequence_number")) {
      while (rows.next()) {
        kinds.add(rows.getString(1));
      }
    }
    return kinds;
  }

  private static String single(Statement statement, String query) throws SQLException {
    try (ResultSet row = statement.executeQuery(query)) {
      assertThat(row.next()).as(query).isTrue();
      return row.getString(1);
    }
  }

  private static void run(Statement statement, String script) throws SQLException {
    for (String sql : PostgresTestSupport.statements(script)) {
      statement.execute(sql);
    }
  }
}
