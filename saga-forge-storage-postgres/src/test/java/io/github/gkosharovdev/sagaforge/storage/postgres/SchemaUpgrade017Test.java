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
 * {@code schema-upgrade-017.sql} on a 0.2.x database (specs/017-saga-deadlines data-model.md "Postgres"): existing
 * rows keep their values and get no open deadlines, the new status and column are accepted, the constraints hold, and
 * a second run changes nothing.
 */
@Tag("container")
class SchemaUpgrade017Test {

    private static final String SCHEMA = "pre017";

    private static final String[] RELEASE_0_2 = {
        "schema-before-006.sql", "schema-upgrade-006.sql", "schema-upgrade-007.sql", "schema-upgrade-010.sql",
        "schema-upgrade-011.sql"};

    @Test
    @DisplayName("adds not_before, CANCELLED and open_deadlines; existing rows are unchanged; a rerun is a no-op")
    void upgradesA02Database() throws SQLException {
        try (Connection connection = PostgresTestSupport.newDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            statement.execute("SET search_path TO " + SCHEMA);
            try {
                for (String script : RELEASE_0_2) {
                    run(statement, script);
                }
                statement.execute("INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at,"
                        + " last_modified_at, steps, outcome) VALUES ('s-1', 'com.example.Saga', '{}'::jsonb,"
                        + " 'COMPLETED', '2026-01-01T00:00:00Z', '2026-01-01T00:00:05Z', '{}'::jsonb, 'end')");
                statement.execute("INSERT INTO saga_message_store (message_id, kind, payload_type, recorded_at,"
                        + " status, next_attempt_at, consumed_at) VALUES ('m-1', 'EVENT', 'T',"
                        + " '2026-01-01T00:00:00Z', 'CONSUMED', '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z')");

                run(statement, "schema-upgrade-017.sql");

                assertThat(single(statement, "SELECT open_deadlines::text FROM saga_instance WHERE saga_id = 's-1'"))
                        .isEqualTo("{}");
                assertThat(single(statement, "SELECT coalesce(not_before::text, 'none') FROM saga_message_store"))
                        .isEqualTo("none");
                statement.execute("INSERT INTO saga_message_store (message_id, kind, payload_type, recorded_at,"
                        + " status, next_attempt_at, consumed_at, not_before) VALUES ('m-2', 'EVENT', 'T',"
                        + " '2026-01-01T00:00:00Z', 'CANCELLED', '2026-01-02T00:00:00Z', '2026-01-01T00:00:01Z',"
                        + " '2026-01-02T00:00:00Z')");
                assertThatThrownBy(() -> statement.execute("UPDATE saga_instance SET open_deadlines ="
                        + " '{\"d\": {}}'::jsonb WHERE saga_id = 's-1'")).isInstanceOf(SQLException.class)
                        .hasMessageContaining("open_deadlines_only_while_active");
                assertThatThrownBy(() -> statement.execute("INSERT INTO saga_message_store (message_id, kind,"
                        + " payload_type, recorded_at, status, next_attempt_at) VALUES ('m-3', 'EVENT', 'T',"
                        + " '2026-01-01T00:00:00Z', 'CANCELLED', '2026-01-01T00:00:00Z')"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("consumed_at_iff_consumed");
                String afterFirstRun = single(statement, "SELECT jsonb_agg(to_jsonb(m) ORDER BY position)::text"
                        + " FROM saga_message_store m");

                run(statement, "schema-upgrade-017.sql");

                assertThat(single(statement, "SELECT jsonb_agg(to_jsonb(m) ORDER BY position)::text"
                        + " FROM saga_message_store m")).isEqualTo(afterFirstRun);
            } finally {
                statement.execute("SET search_path TO public");
                statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
            }
        }
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
