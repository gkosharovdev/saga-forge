package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A host without the Spring Boot starter runs Saga Forge's migrations with its own Flyway and
 * checks the result ({@code specs/018-versioned-schema-migrations} T036, US4). {@link #migrate} is
 * the snippet in {@code docs/guide/schema.md}.
 */
@Tag("container")
class PlainJavaMigrationExampleTest {

  // tag::migrate[]
  static void migrate(DataSource dataSource) {
    PostgresSchemaValidator validator = new PostgresSchemaValidator(dataSource);
    String history = PostgresSchema.DEFAULT_HISTORY_TABLE;

    // A database built from schema.sql and the upgrade scripts has Saga Forge's tables but no
    // history: record the version it is at as the baseline. Otherwise the baseline is 0, which
    // lets Flyway start beside the application's own tables.
    String baseline = "0";
    if (validator.appliedVersion(history).isEmpty()) {
      switch (validator.detectVersion()) {
        case SchemaDetection.Matched matched ->
            baseline = String.valueOf(matched.version().number());
        case SchemaDetection.Unmatched unmatched -> throw SagaSchemaException.unmatched(unmatched);
        case SchemaDetection.Empty empty -> {}
      }
    }

    Flyway.configure()
        .dataSource(dataSource)
        .locations(PostgresSchema.MIGRATION_LOCATION)
        .table(history)
        .placeholderReplacement(false)
        .baselineOnMigrate(true)
        .baselineVersion(baseline)
        .load()
        .migrate();

    SchemaValidationReport report = validator.validate();
    if (!report.passed()) {
      throw SagaSchemaException.failedCheck(report);
    }
  }

  // end::migrate[]

  @Test
  @DisplayName("an empty database ends equal to schema.sql, and its report passes")
  void emptyDatabase() throws Exception {
    DataSource migrated = PostgresTestSupport.freshSchema("plain_migrated");
    migrate(migrated);
    DataSource script = PostgresTestSupport.freshSchema("plain_script");
    PostgresTestSupport.runScript(
        script, "/io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql");

    assertThat(SchemaSnapshotTest.readAll(migrated).toJson())
        .isEqualTo(SchemaSnapshotTest.readAll(script).toJson());
    assertThat(new PostgresSchemaValidator(migrated).validate().passed()).isTrue();
  }

  @Test
  @DisplayName("a released 0.1.0 database is baselined at V1")
  void releasedDatabase() {
    DataSource dataSource = PostgresTestSupport.freshSchema("plain_released");
    PostgresTestSupport.runScript(
        dataSource, "/io/github/gkosharovdev/sagaforge/storage/postgres/released/schema-0.1.0.sql");

    migrate(dataSource);

    assertThat(historyRows(dataSource))
        .containsExactly("BASELINE:1", "SQL:2", "SQL:3", "SQL:4", "SQL:5", "SQL:6");
  }

  private static List<String> historyRows(DataSource dataSource) {
    List<String> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT type || ':' || version FROM "
                    + PostgresSchema.DEFAULT_HISTORY_TABLE
                    + " ORDER BY installed_rank")) {
      while (result.next()) {
        rows.add(result.getString(1));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return rows;
  }

  @Test
  @DisplayName("a dropped column fails with the report's text")
  void droppedColumn() {
    DataSource dataSource = PostgresTestSupport.freshSchema("plain_dropped");
    migrate(dataSource);
    PostgresTestSupport.execute(
        dataSource, "ALTER TABLE saga_context DROP COLUMN last_modified_at");

    assertThatThrownBy(() -> migrate(dataSource))
        .isInstanceOf(SagaSchemaException.class)
        .hasMessageContaining(
            "saga_context: column last_modified_at is missing (expected timestamp with time zone"
                + " NOT NULL)");
  }
}
