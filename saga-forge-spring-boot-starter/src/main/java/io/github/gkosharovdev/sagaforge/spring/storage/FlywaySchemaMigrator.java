package io.github.gkosharovdev.sagaforge.spring.storage;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchemaValidator;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaVersion;
import java.lang.System.Logger.Level;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.util.ClassUtils;

/**
 * Applies Saga Forge's versioned migrations with its own {@link Flyway} instance: Saga Forge's
 * location and history table, no placeholders, Flyway's defaults otherwise (checksums validated,
 * future versions ignored, clean disabled). Independent of the application's Flyway bean and of
 * {@code spring.flyway.*} ({@code specs/018-versioned-schema-migrations} research R7). Concurrent
 * starts are serialised by Flyway's Postgres advisory lock (R8).
 */
final class FlywaySchemaMigrator implements SchemaMigrator {

  static final String POSTGRES_SUPPORT = "org.flywaydb.database.postgresql.PostgreSQLDatabaseType";

  private static final System.Logger LOG = System.getLogger(FlywaySchemaMigrator.class.getName());

  private final DataSource dataSource;
  private final String historyTable;
  private final ClassLoader classLoader;

  FlywaySchemaMigrator(DataSource dataSource, String historyTable, ClassLoader classLoader) {
    this.dataSource = dataSource;
    this.historyTable = historyTable;
    this.classLoader = classLoader;
  }

  @Override
  public SchemaVersion migrate(SchemaVersion baseline) {
    if (!ClassUtils.isPresent(POSTGRES_SUPPORT, classLoader)) {
      throw SchemaInitializer.flywayMissing("org.flywaydb:flyway-database-postgresql");
    }
    FluentConfiguration configuration =
        Flyway.configure(classLoader)
            .dataSource(dataSource)
            .locations(PostgresSchema.MIGRATION_LOCATION)
            .table(historyTable)
            .placeholderReplacement(false)
            // The schema usually holds the application's own tables, which Flyway would refuse
            // without a baseline. With none of Saga Forge's tables the baseline is version 0, so
            // V1 onwards are applied; Flyway ignores it once its history table exists.
            .baselineOnMigrate(true)
            .baselineVersion(baseline == null ? "0" : String.valueOf(baseline.number()));
    MigrateResult result;
    try {
      result = configuration.load().migrate();
    } catch (RuntimeException e) {
      throw new SagaForgeConfigurationException(
          "Saga Forge's schema migration failed: " + e.getMessage(),
          "Fix the cause; the failed version was rolled back and is applied again at the next"
              + " start.");
    }
    for (MigrateOutput migration : result.migrations) {
      LOG.log(
          Level.INFO,
          "Saga Forge schema version {0} ({1}) applied",
          migration.version,
          migration.description);
    }
    return new PostgresSchemaValidator(dataSource)
        .appliedVersion(historyTable)
        .orElseThrow(() -> new IllegalStateException(historyTable + " holds no version"));
  }
}
