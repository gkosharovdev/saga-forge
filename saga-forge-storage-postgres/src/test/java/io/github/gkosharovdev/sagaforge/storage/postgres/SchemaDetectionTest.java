package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDetection.Matched;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDetection.Unmatched;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Finding the version of a database without Saga Forge's history (specs/018 research R6). */
@Tag("container")
class SchemaDetectionTest {

  private static final String RELEASED =
      "/io/github/gkosharovdev/sagaforge/storage/postgres/released/schema-";

  @Test
  @DisplayName("a schema without Saga Forge's tables is Empty")
  void empty() {
    DataSource dataSource = PostgresTestSupport.freshSchema("detect_empty");
    PostgresTestSupport.execute(dataSource, "CREATE TABLE app_table (id INT)");

    assertThat(new PostgresSchemaValidator(dataSource).detectVersion())
        .isInstanceOf(SchemaDetection.Empty.class);
  }

  @ParameterizedTest(name = "release {0} is V{1}")
  @CsvSource({"0.1.0, 1", "0.2.0, 2", "0.4.0, 4"})
  @DisplayName("each released schema.sql is detected as its version")
  void releasedLevels(String release, int version) {
    DataSource dataSource = PostgresTestSupport.freshSchema("detect_" + release.replace('.', '_'));
    PostgresTestSupport.runScript(dataSource, RELEASED + release + ".sql");
    PostgresTestSupport.execute(dataSource, "CREATE TABLE app_table (id INT)");

    assertThat(new PostgresSchemaValidator(dataSource).detectVersion())
        .isInstanceOfSatisfying(
            Matched.class, matched -> assertThat(matched.version().number()).isEqualTo(version));
  }

  @Test
  @DisplayName("a database migrated to V3 is detected as V3")
  void migratedToV3() {
    DataSource dataSource = PostgresTestSupport.freshSchema("detect_v3");
    FlywayTestSupport.migrate(dataSource, 3);

    assertThat(new PostgresSchemaValidator(dataSource).detectVersion())
        .isInstanceOfSatisfying(
            Matched.class, matched -> assertThat(matched.version().number()).isEqualTo(3));
  }

  @Test
  @DisplayName("renamed constraints and indexes are still matched")
  void renamedObjects() throws Exception {
    DataSource dataSource = PostgresTestSupport.freshSchema("detect_renamed");
    PostgresTestSupport.runScript(dataSource, RELEASED + "0.1.0.sql");
    for (String[] renaming : constraintsAndIndexes(dataSource)) {
      PostgresTestSupport.execute(dataSource, renaming[0]);
    }

    assertThat(new PostgresSchemaValidator(dataSource).detectVersion())
        .isInstanceOfSatisfying(
            Matched.class, matched -> assertThat(matched.version().number()).isEqualTo(1));
  }

  @Test
  @DisplayName("a dropped column at 0.4.0 is Unmatched, closest V4, naming the column")
  void unmatched() {
    DataSource dataSource = PostgresTestSupport.freshSchema("detect_unmatched");
    PostgresTestSupport.runScript(dataSource, RELEASED + "0.4.0.sql");
    PostgresTestSupport.execute(
        dataSource, "ALTER TABLE saga_instance DROP COLUMN outcome CASCADE");

    assertThat(new PostgresSchemaValidator(dataSource).detectVersion())
        .isInstanceOfSatisfying(
            Unmatched.class,
            unmatched -> {
              assertThat(unmatched.closest().expected().number()).isEqualTo(4);
              assertThat(unmatched.closest().describe())
                  .contains("saga_instance: column outcome is missing");
            });
  }

  @Test
  @DisplayName("appliedVersion reads Flyway's history: none, baseline only, migrated")
  void appliedVersion() {
    DataSource none = PostgresTestSupport.freshSchema("applied_none");
    assertThat(
            new PostgresSchemaValidator(none).appliedVersion(PostgresSchema.DEFAULT_HISTORY_TABLE))
        .isEmpty();

    DataSource baseline = PostgresTestSupport.freshSchema("applied_baseline");
    PostgresTestSupport.runScript(baseline, RELEASED + "0.4.0.sql");
    FlywayTestSupport.flyway(baseline).baselineVersion("4").load().baseline();
    assertThat(
            new PostgresSchemaValidator(baseline)
                .appliedVersion(PostgresSchema.DEFAULT_HISTORY_TABLE))
        .contains(SchemaVersion.of(4));

    DataSource migrated = PostgresTestSupport.freshSchema("applied_migrated");
    FlywayTestSupport.migrate(migrated);
    assertThat(
            new PostgresSchemaValidator(migrated)
                .appliedVersion(PostgresSchema.DEFAULT_HISTORY_TABLE))
        .contains(PostgresSchema.currentVersion());
  }

  /** {@code ALTER ... RENAME} statements giving every constraint and index a new name. */
  private static List<String[]> constraintsAndIndexes(DataSource dataSource) throws Exception {
    List<String[]> statements = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT 'ALTER TABLE ' || c.relname || ' RENAME CONSTRAINT ' || con.conname"
                    + " || ' TO renamed_' || con.oid FROM pg_constraint con"
                    + " JOIN pg_class c ON c.oid = con.conrelid JOIN pg_namespace n ON n.oid ="
                    + " c.relnamespace WHERE n.nspname = current_schema()"
                    + " UNION ALL SELECT 'ALTER INDEX ' || i.relname || ' RENAME TO renamed_' ||"
                    + " i.oid FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid JOIN"
                    + " pg_namespace n ON n.oid = i.relnamespace WHERE n.nspname ="
                    + " current_schema() AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE"
                    + " con.conindid = x.indexrelid)")) {
      while (rows.next()) {
        statements.add(new String[] {rows.getString(1)});
      }
    }
    return statements;
  }
}
