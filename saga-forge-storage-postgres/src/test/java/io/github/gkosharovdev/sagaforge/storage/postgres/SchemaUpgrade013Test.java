package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code schema-upgrade-013.sql} on a database of the 017 schema ({@code
 * specs/013-active-standby-relay} data-model.md "Postgres"): the heartbeat table is dropped, the
 * leadership table is created with its constraints, and a second run changes nothing.
 */
@Tag("container")
class SchemaUpgrade013Test {

  private static final String SCHEMA = "pre013";

  private static final String[] SCHEMA_017 = {
    "schema-before-006.sql",
    "schema-upgrade-006.sql",
    "schema-upgrade-007.sql",
    "schema-upgrade-010.sql",
    "schema-upgrade-011.sql",
    "schema-upgrade-017.sql"
  };

  @Test
  @DisplayName("drops the consumer table, adds saga_message_store_leader; a rerun is a no-op")
  void upgradesA017Database() throws SQLException {
    try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
      statement.execute("CREATE SCHEMA " + SCHEMA);
      statement.execute("SET search_path TO " + SCHEMA);
      try {
        for (String script : SCHEMA_017) {
          run(statement, script);
        }
        statement.execute(
            "INSERT INTO saga_message_store_consumer (consumer_id, host, started_at, last_seen_at)"
                + " VALUES ('c', 'h', '2026-01-01T00:00:00Z', '2026-01-01T00:00:05Z')");

        run(statement, "schema-upgrade-013.sql");

        assertThat(tableCount(statement, "saga_message_store_consumer")).isZero();
        statement.execute(
            "INSERT INTO saga_message_store_leader (id, holder_id, holder_host, fencing_token,"
                + " acquired_at, expires_at) VALUES (1, 'A', 'host-A', 1, '2026-01-01T00:00:00Z',"
                + " '2026-01-01T00:00:30Z')");
        assertThatThrownBy(
                () ->
                    statement.execute(
                        "INSERT INTO saga_message_store_leader VALUES (2, 'B', 'host-B', 1,"
                            + " '2026-01-01T00:00:00Z', '2026-01-01T00:00:30Z')"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("saga_message_store_leader_id_check");
        assertThatThrownBy(
                () ->
                    statement.execute(
                        "UPDATE saga_message_store_leader SET expires_at = '2025-12-31T00:00:00Z'"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("expires_after_acquired");

        run(statement, "schema-upgrade-013.sql");

        assertThat(single(statement, "SELECT holder_id FROM saga_message_store_leader"))
            .isEqualTo("A");
      } finally {
        statement.execute("SET search_path TO public");
        statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
      }
    }
  }

  private static int tableCount(Statement statement, String table) throws SQLException {
    return Integer.parseInt(
        single(
            statement,
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = '"
                + SCHEMA
                + "' AND table_name = '"
                + table
                + "'"));
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
