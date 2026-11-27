package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.ObjectKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.Problem;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.Severity;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The schema check against Postgres (specs/018 FR-011 to FR-014, SC-004). */
@Tag("container")
class PostgresSchemaValidatorTest {

  private static DataSource migrated(String schema) {
    DataSource dataSource = PostgresTestSupport.freshSchema(schema);
    FlywayTestSupport.migrate(dataSource);
    return dataSource;
  }

  @Test
  @DisplayName("a migrated schema passes with no difference")
  void migratedPasses() {
    SchemaValidationReport report = new PostgresSchemaValidator(migrated("validate_ok")).validate();

    assertThat(report.passed()).isTrue();
    assertThat(report.differences()).isEmpty();
    assertThat(report.expected()).isEqualTo(PostgresSchema.currentVersion());
  }

  @ParameterizedTest(name = "{0}.{1}")
  @CsvSource({
    "saga_instance, outcome",
    "saga_association, association_value",
    "saga_context, last_modified_at",
    "saga_lifecycle_history, operator_reason",
    "saga_message_store, not_before",
    "saga_message_store_leader, holder_host"
  })
  @DisplayName("a dropped column is one failure naming its table and column")
  void droppedColumnFails(String table, String column) {
    DataSource dataSource = migrated("validate_drop_" + table);
    PostgresTestSupport.execute(
        dataSource, "ALTER TABLE " + table + " DROP COLUMN " + column + " CASCADE");

    SchemaValidationReport report = new PostgresSchemaValidator(dataSource).validate();

    assertThat(report.passed()).isFalse();
    assertThat(report.failures())
        .filteredOn(d -> d.kind() == ObjectKind.COLUMN)
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.table()).isEqualTo(table);
              assertThat(d.name()).isEqualTo(column);
              assertThat(d.problem()).isEqualTo(Problem.MISSING);
            });
    assertThat(report.describe()).contains(table + ": column " + column + " is missing");
  }

  @Test
  @DisplayName("a wrong type and a dropped check fail, a dropped index warns, extras are ignored")
  void mixedDifferences() {
    DataSource dataSource = migrated("validate_mixed");
    PostgresTestSupport.execute(
        dataSource, "ALTER TABLE saga_instance ALTER COLUMN compensation_round TYPE BIGINT");
    PostgresTestSupport.execute(
        dataSource, "ALTER TABLE saga_instance DROP CONSTRAINT compensation_round_not_negative");
    PostgresTestSupport.execute(dataSource, "DROP INDEX saga_instance_status_modified");
    PostgresTestSupport.execute(dataSource, "ALTER TABLE saga_instance ADD COLUMN app_note TEXT");
    PostgresTestSupport.execute(dataSource, "CREATE TABLE app_table (id INT PRIMARY KEY)");
    PostgresTestSupport.execute(
        dataSource, "CREATE INDEX app_index ON saga_instance (saga_type, app_note)");

    SchemaValidationReport report = new PostgresSchemaValidator(dataSource).validate();

    assertThat(report.failures())
        .extracting(SchemaDifference::kind, SchemaDifference::problem)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(ObjectKind.COLUMN, Problem.DIFFERENT),
            org.assertj.core.groups.Tuple.tuple(ObjectKind.CONSTRAINT, Problem.MISSING));
    assertThat(report.warnings())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.kind()).isEqualTo(ObjectKind.INDEX);
              assertThat(d.severity()).isEqualTo(Severity.WARNING);
              assertThat(d.name()).isEqualTo("USING btree (status, last_modified_at, saga_id)");
            });
    assertThat(report.describe())
        .startsWith(
            "Saga Forge's schema does not match V5 (saga operations): 2 failures, 1 warning.")
        .contains(
            "FAILURE saga_instance: column compensation_round is different (expected integer"
                + " NOT NULL, found bigint NOT NULL)")
        .doesNotContain("app_note")
        .doesNotContain("app_table");
  }

  @Test
  @DisplayName("checking writes nothing")
  void readsOnly() throws Exception {
    DataSource dataSource = migrated("validate_readonly");
    String before = catalogFingerprint(dataSource);

    new PostgresSchemaValidator(dataSource).validate();
    new PostgresSchemaValidator(dataSource).detectVersion();
    new PostgresSchemaValidator(dataSource).appliedVersion(PostgresSchema.DEFAULT_HISTORY_TABLE);

    assertThat(catalogFingerprint(dataSource)).isEqualTo(before);
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.isReadOnly()).isFalse();
      assertThat(connection.getAutoCommit()).isTrue();
    }
  }

  private static String catalogFingerprint(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT string_agg(c.relname || ':' || c.relnatts || ':' || c.reltuples, ',' ORDER BY"
                    + " c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = current_schema()")) {
      rows.next();
      return rows.getString(1)
          + "/history="
          + PostgresTestSupport.queryRows(
                  "SELECT count(*) AS n FROM validate_readonly."
                      + PostgresSchema.DEFAULT_HISTORY_TABLE)
              .getFirst()
              .get("n");
    }
  }
}
