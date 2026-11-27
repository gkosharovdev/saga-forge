package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * {@code schema-upgrade-015.sql} on a 0.4.x database (specs/015-saga-operations data-model.md
 * "Storage: Postgres"): existing rows get no resolution and round 0, the upgraded tables match a
 * fresh {@code schema.sql} column for column, the checks and the index exist, and a rerun changes
 * nothing.
 */
@Tag("container")
class SchemaUpgrade015Test {

  private static final String UPGRADED = "pre015";
  private static final String FRESH = "fresh015";

  private static final String[] RELEASE_0_4 = {
    "schema-before-006.sql",
    "schema-upgrade-006.sql",
    "schema-upgrade-007.sql",
    "schema-upgrade-010.sql",
    "schema-upgrade-011.sql",
    "schema-upgrade-017.sql",
    "schema-upgrade-013.sql"
  };

  @Test
  @DisplayName("adds resolution, compensation_round, the operator columns and the listing index")
  void upgradesA04Database() throws SQLException {
    try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
        Statement statement = connection.createStatement()) {
      recreate(statement, FRESH);
      recreate(statement, UPGRADED);
      try {
        statement.execute("SET search_path TO " + FRESH);
        run(statement, "schema.sql");
        statement.execute("SET search_path TO " + UPGRADED);
        for (String script : RELEASE_0_4) {
          run(statement, script);
        }
        statement.execute(
            "INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at,"
                + " last_modified_at, steps, outcome) VALUES ('s-1', 'com.example.Saga', '{}'::jsonb,"
                + " 'COMPLETED', '2026-01-01T00:00:00Z', '2026-01-01T00:00:05Z', '{}'::jsonb, 'end')");

        run(statement, "schema-upgrade-015.sql");

        assertThat(
                single(
                    statement,
                    "SELECT coalesce(resolution::text, 'none') || '/' || compensation_round"
                        + " FROM saga_instance WHERE saga_id = 's-1'"))
            .isEqualTo("none/0");
        assertThatThrownBy(
                () ->
                    statement.execute(
                        "UPDATE saga_instance SET resolution = '{}'::jsonb WHERE saga_id = 's-1'"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("resolution_only_when_failed");
        assertThatThrownBy(
                () ->
                    statement.execute(
                        "UPDATE saga_instance SET compensation_round = -1 WHERE saga_id = 's-1'"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("compensation_round_not_negative");
        for (String table : List.of("saga_instance", "saga_lifecycle_history")) {
          assertThat(columns(statement, UPGRADED, table))
              .as(table)
              .containsExactlyInAnyOrderElementsOf(
                  columns(statement, FRESH, table).stream()
                      // 019 added the metadata column after 015.
                      .filter(column -> !column.startsWith("metadata "))
                      .toList());
        }
        assertThat(
                single(
                    statement,
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = '"
                        + UPGRADED
                        + "' AND indexname = 'saga_instance_status_modified'"))
            .contains("(status, last_modified_at, saga_id)");

        String afterFirstRun = single(statement, "SELECT to_jsonb(i)::text FROM saga_instance i");
        run(statement, "schema-upgrade-015.sql");

        assertThat(single(statement, "SELECT to_jsonb(i)::text FROM saga_instance i"))
            .isEqualTo(afterFirstRun);
      } finally {
        statement.execute("SET search_path TO public");
        statement.execute("DROP SCHEMA IF EXISTS " + UPGRADED + " CASCADE");
        statement.execute("DROP SCHEMA IF EXISTS " + FRESH + " CASCADE");
      }
    }
  }

  private static void recreate(Statement statement, String schema) throws SQLException {
    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    statement.execute("CREATE SCHEMA " + schema);
  }

  private static List<String> columns(Statement statement, String schema, String table)
      throws SQLException {
    List<String> columns = new ArrayList<>();
    try (ResultSet rows =
        statement.executeQuery(
            "SELECT column_name || ' ' || data_type || ' ' || is_nullable || ' '"
                + " || coalesce(column_default, '-') FROM information_schema.columns"
                + " WHERE table_schema = '"
                + schema
                + "' AND table_name = '"
                + table
                + "'")) {
      while (rows.next()) {
        columns.add(rows.getString(1));
      }
    }
    return columns;
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
