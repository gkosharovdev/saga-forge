package io.github.gkosharovdev.sagaforge.spring.storage;

import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fresh Postgres schemas for the migration tests ({@code specs/018-versioned-schema-migrations}),
 * on the starter's shared container: each test gets its own schema as {@code current_schema()}.
 */
final class MigrationSchemas {

  static final String SAGA_FORGE_HISTORY = "saga_forge_schema_history";
  static final String APP_HISTORY = "flyway_schema_history";

  private MigrationSchemas() {}

  /** A new, empty schema. */
  static String create() {
    String schema = "m_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(StarterPostgres.dataSource()).execute("CREATE SCHEMA " + schema);
    return schema;
  }

  static DataSource dataSource(String schema) {
    PGSimpleDataSource dataSource = (PGSimpleDataSource) StarterPostgres.dataSource();
    dataSource.setCurrentSchema(schema);
    return dataSource;
  }

  static JdbcTemplate jdbc(String schema) {
    return new JdbcTemplate(dataSource(schema));
  }

  /** The JDBC URL of {@code schema}, for {@code spring.datasource.url}. */
  static String url(String schema) {
    String url = StarterPostgres.jdbcUrl();
    return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  /** Runs a class-path script, statements split at {@code ;} ends of lines, comments dropped. */
  static void run(String schema, String resource) {
    JdbcTemplate jdbc = jdbc(schema);
    for (String statement : statements(read(resource))) {
      jdbc.execute(statement);
    }
  }

  static String read(String resource) {
    try (InputStream in = MigrationSchemas.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalArgumentException("no resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> statements(String script) {
    String code =
        script
            .lines()
            .filter(line -> !line.strip().startsWith("--"))
            .collect(Collectors.joining("\n"));
    return Arrays.stream(code.split(";\\s*(\\n|$)"))
        .map(String::strip)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  /**
   * Writes the application's own migrations {@code V1} to {@code V<count>} into {@code dir}, each
   * creating a table {@code app_t<N>}; {@code overrides} replaces the SQL of the given versions.
   *
   * @return the Flyway location, {@code filesystem:<dir>}
   */
  static String applicationMigrations(Path dir, int count, Map<Integer, String> overrides) {
    try {
      Files.createDirectories(dir);
      for (int n = 1; n <= count; n++) {
        String sql = overrides.getOrDefault(n, "CREATE TABLE app_t" + n + " (id INT);\n");
        Files.writeString(dir.resolve("V" + n + "__app_step_" + n + ".sql"), sql);
      }
      return "filesystem:" + dir.toAbsolutePath();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The successful versions in {@code table}, in order, baseline rows included. */
  static List<String> versions(String schema, String table) {
    return jdbc(schema)
        .queryForList(
            "SELECT version FROM " + table + " WHERE success ORDER BY installed_rank",
            String.class);
  }

  /** The type of each row in {@code table} ({@code SQL}, {@code BASELINE}), in order. */
  static List<String> types(String schema, String table) {
    return jdbc(schema)
        .queryForList("SELECT type FROM " + table + " ORDER BY installed_rank", String.class);
  }

  static boolean exists(String schema, String table) {
    return jdbc(schema)
        .queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, schema + "." + table);
  }

  static int count(String schema, String table) {
    return jdbc(schema).queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }
}
