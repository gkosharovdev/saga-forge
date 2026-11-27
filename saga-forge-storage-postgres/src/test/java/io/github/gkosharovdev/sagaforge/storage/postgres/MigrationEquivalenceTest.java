package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every path to the current schema ends at the same tables, columns, defaults, constraints and
 * indexes (specs/018 FR-003, SC-003): the migrations V1 to V5, the current {@code schema.sql}, and
 * each released {@code schema.sql} plus the upgrade scripts written after it, in the order their
 * changes reached {@code schema.sql}.
 */
@Tag("container")
class MigrationEquivalenceTest {

  private static final String RESOURCES = "/io/github/gkosharovdev/sagaforge/storage/postgres/";

  /** The upgrade scripts after release 0.1.0, in the order their changes reached schema.sql. */
  private static final List<String> UPGRADES_AFTER_0_1 =
      List.of(
          "schema-upgrade-011.sql",
          "schema-upgrade-017.sql",
          "schema-upgrade-013.sql",
          "schema-upgrade-015.sql");

  @Test
  @DisplayName("the migrations end at the current schema.sql")
  void migrationsEqualSchemaScript() throws Exception {
    DataSource migrated = PostgresTestSupport.freshSchema("equiv_migrated");
    FlywayTestSupport.migrate(migrated);
    DataSource script = PostgresTestSupport.freshSchema("equiv_script");
    PostgresTestSupport.runScript(script, RESOURCES + "schema.sql");

    assertThat(shape(migrated)).isEqualTo(shape(script));
    assertThat(SchemaSnapshotTest.readAll(migrated).toJson())
        .isEqualTo(SchemaSnapshot.load(PostgresSchema.currentVersion()).toJson());
  }

  @ParameterizedTest(name = "release {0}")
  @CsvSource({"0.1.0, 0", "0.2.0, 1", "0.4.0, 3"})
  @DisplayName("each released schema.sql plus its later upgrade scripts ends at the migrations")
  void releasedLevelsUpgradeToTheSameSchema(String release, int upgradesAlreadyIn)
      throws Exception {
    DataSource upgraded = PostgresTestSupport.freshSchema("equiv_" + release.replace('.', '_'));
    PostgresTestSupport.runScript(upgraded, RESOURCES + "released/schema-" + release + ".sql");
    for (String upgrade :
        UPGRADES_AFTER_0_1.subList(upgradesAlreadyIn, UPGRADES_AFTER_0_1.size())) {
      PostgresTestSupport.runScript(upgraded, RESOURCES + upgrade);
    }
    DataSource migrated =
        PostgresTestSupport.freshSchema("equiv_migrated_" + release.replace('.', '_'));
    FlywayTestSupport.migrate(migrated);

    assertThat(shape(upgraded)).isEqualTo(shape(migrated));
  }

  /** The catalog shape plus column defaults, which the schema check leaves out. */
  private static String shape(DataSource dataSource) throws Exception {
    Map<String, String> defaults = new TreeMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT c.relname || '.' || a.attname, pg_get_expr(d.adbin, d.adrelid)"
                    + " FROM pg_attrdef d JOIN pg_class c ON c.oid = d.adrelid"
                    + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum"
                    + " WHERE n.nspname = current_schema()"
                    + " AND c.relname <> '"
                    + PostgresSchema.DEFAULT_HISTORY_TABLE
                    + "'")) {
      while (rows.next()) {
        defaults.put(rows.getString(1), rows.getString(2));
      }
    }
    return SchemaSnapshotTest.readAll(dataSource).toJson() + "\ndefaults: " + defaults;
  }
}
