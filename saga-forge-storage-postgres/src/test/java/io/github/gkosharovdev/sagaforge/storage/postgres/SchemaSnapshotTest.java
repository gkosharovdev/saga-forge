package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The shipped snapshots {@code schema-levels/V<N>.json} equal the shape of a database migrated to
 * that version (specs/018 research R5). Run with {@code -Dsaga-forge.write-snapshots=true} to
 * rewrite them under {@code src/main/resources} from the migrations.
 */
@Tag("container")
class SchemaSnapshotTest {

  private static final Path SNAPSHOT_DIR =
      Path.of("src/main/resources/io/github/gkosharovdev/sagaforge/storage/postgres/schema-levels");

  static List<SchemaVersion> versions() {
    return PostgresSchema.versions();
  }

  @ParameterizedTest
  @MethodSource("versions")
  @DisplayName("the snapshot of each version equals a database migrated to it")
  void snapshotMatchesMigratedDatabase(SchemaVersion version) throws Exception {
    DataSource dataSource = PostgresTestSupport.freshSchema("snapshot_v" + version.number());
    FlywayTestSupport.migrate(dataSource, version.number());

    SchemaSnapshot migrated = readAll(dataSource);

    if (Boolean.getBoolean("saga-forge.write-snapshots")) {
      Files.createDirectories(SNAPSHOT_DIR);
      Files.writeString(
          SNAPSHOT_DIR.resolve("V" + version.number() + ".json"),
          migrated.toJson(),
          StandardCharsets.UTF_8);
      return;
    }
    assertThat(migrated.toJson()).isEqualTo(SchemaSnapshot.load(version).toJson());
  }

  /** The shape of every table in the schema except Flyway's history table. */
  static SchemaSnapshot readAll(DataSource dataSource) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      List<String> tables = new ArrayList<>();
      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT table_name FROM information_schema.tables"
                      + " WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'")) {
        while (rows.next()) {
          if (!rows.getString(1).equals(PostgresSchema.DEFAULT_HISTORY_TABLE)) {
            tables.add(rows.getString(1));
          }
        }
      }
      return CatalogReader.read(connection, tables);
    }
  }
}
