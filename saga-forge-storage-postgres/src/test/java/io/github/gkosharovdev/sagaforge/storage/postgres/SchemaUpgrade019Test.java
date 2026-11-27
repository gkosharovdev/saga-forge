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
 * {@code schema-upgrade-019.sql} on a 0.5.x database (specs/019-process-metadata data-model.md
 * "Storage: Postgres", SC-004): existing rows read as empty metadata, the upgraded tables match a
 * fresh {@code schema.sql} column for column, and a rerun changes nothing.
 */
@Tag("container")
class SchemaUpgrade019Test {

  private static final String UPGRADED = "pre019";
  private static final String FRESH = "fresh019";

  private static final String[] RELEASE_0_5 = {
    "schema-before-006.sql",
    "schema-upgrade-006.sql",
    "schema-upgrade-007.sql",
    "schema-upgrade-010.sql",
    "schema-upgrade-011.sql",
    "schema-upgrade-017.sql",
    "schema-upgrade-013.sql",
    "schema-upgrade-015.sql"
  };

  @Test
  @DisplayName("adds the metadata column to instances, history and messages; old rows are empty")
  void upgradesA05Database() throws SQLException {
    try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
        Statement statement = connection.createStatement()) {
      recreate(statement, FRESH);
      recreate(statement, UPGRADED);
      try {
        statement.execute("SET search_path TO " + FRESH);
        run(statement, "schema.sql");
        statement.execute("SET search_path TO " + UPGRADED);
        for (String script : RELEASE_0_5) {
          run(statement, script);
        }
        statement.execute(
            "INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at,"
                + " last_modified_at, steps, outcome) VALUES ('s-1', 'com.example.Saga', '{}'::jsonb,"
                + " 'COMPLETED', '2026-01-01T00:00:00Z', '2026-01-01T00:00:05Z', '{}'::jsonb, 'end')");

        run(statement, "schema-upgrade-019.sql");

        assertThat(
                single(statement, "SELECT metadata::text FROM saga_instance WHERE saga_id = 's-1'"))
            .isEqualTo("{}");
        for (String table :
            List.of("saga_instance", "saga_lifecycle_history", "saga_message_store")) {
          assertThat(columns(statement, UPGRADED, table))
              .as(table)
              .containsExactlyInAnyOrderElementsOf(columns(statement, FRESH, table));
        }

        String afterFirstRun = single(statement, "SELECT to_jsonb(i)::text FROM saga_instance i");
        run(statement, "schema-upgrade-019.sql");

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
        columns.add(rows.getString(1).replace(schema + ".", ""));
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
