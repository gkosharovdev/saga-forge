package io.github.gkosharovdev.sagaforge.storage.postgres;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Applies the shipped migrations with Flyway in tests (018); the module itself never uses Flyway.
 */
final class FlywayTestSupport {

  private FlywayTestSupport() {}

  /**
   * @param dataSource the schema to migrate (its {@code current_schema()})
   * @param target the version to stop at
   */
  static void migrate(DataSource dataSource, int target) {
    flyway(dataSource).target(String.valueOf(target)).load().migrate();
  }

  /** Applies every shipped version. */
  static void migrate(DataSource dataSource) {
    flyway(dataSource).load().migrate();
  }

  static org.flywaydb.core.api.configuration.FluentConfiguration flyway(DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations(PostgresSchema.MIGRATION_LOCATION)
        .table(PostgresSchema.DEFAULT_HISTORY_TABLE)
        .placeholderReplacement(false);
  }
}
