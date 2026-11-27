package io.github.gkosharovdev.sagaforge.spring.storage;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.Migration;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.SchemaInitialization;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchemaValidator;
import io.github.gkosharovdev.sagaforge.storage.postgres.SagaSchemaException;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDetection;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaValidationReport;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaVersion;
import java.lang.System.Logger.Level;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.ClassUtils;

/**
 * Applies {@code saga-forge.storage.postgres.initialize-schema} at startup ({@code
 * specs/018-versioned-schema-migrations}):
 *
 * <ul>
 *   <li>{@code never} (default): fails naming the missing tables; otherwise checks the schema and
 *       logs differences.
 *   <li>{@code always}: creates the tables from {@code schema.sql} in a database without them, then
 *       as {@code never}.
 *   <li>{@code validate}: fails when the schema check finds a failure.
 *   <li>{@code migrate}: applies Saga Forge's missing versioned migrations under its own history
 *       table, taking over a database built from {@code schema.sql} and the upgrade scripts, then
 *       checks the result.
 * </ul>
 */
public class SchemaInitializer {

  /** The schema script on the class path, from {@code saga-forge-storage-postgres}. */
  public static final String SCHEMA = PostgresSchema.SCHEMA_SCRIPT;

  static final List<String> TABLES =
      List.of(
          "saga_instance",
          "saga_association",
          "saga_context",
          "saga_lifecycle_history",
          "saga_message_store",
          "saga_message_store_leader");

  static final String FLYWAY_CORE = "org.flywaydb:flyway-core";
  static final String FLYWAY_POSTGRES = "org.flywaydb:flyway-database-postgresql";

  private static final System.Logger LOG = System.getLogger(SchemaInitializer.class.getName());

  private final DataSource dataSource;
  private final SchemaInitialization policy;
  private final PostgresSchemaValidator validator;
  private final SchemaMigrator migrator;
  private final Migration migration;
  private final ClassLoader classLoader;
  private volatile SchemaStatus status;

  /**
   * An initializer with the default migration settings and no migrator ({@code migrate} then fails
   * as if Flyway were absent).
   */
  public SchemaInitializer(DataSource dataSource, SchemaInitialization policy) {
    this(
        dataSource,
        policy,
        new PostgresSchemaValidator(dataSource),
        null,
        new Migration(),
        SchemaInitializer.class.getClassLoader());
  }

  SchemaInitializer(
      DataSource dataSource,
      SchemaInitialization policy,
      PostgresSchemaValidator validator,
      SchemaMigrator migrator,
      Migration migration,
      ClassLoader classLoader) {
    this.dataSource = dataSource;
    this.policy = policy;
    this.validator = validator;
    this.migrator = migrator;
    this.migration = migration;
    this.classLoader = classLoader;
    this.status = new SchemaStatus(policy, null, null);
  }

  /**
   * Applies the policy.
   *
   * @throws SagaForgeConfigurationException with {@code never}, if a table is missing; with {@code
   *     always}, if only some tables exist; with {@code migrate}, if Flyway is absent or the
   *     baseline version is unknown
   * @throws SagaSchemaException with {@code validate} or {@code migrate}, if the schema check fails
   *     or the tables match no released version
   */
  public void initialize() {
    switch (policy) {
      case NEVER, ALWAYS -> checkTablesExist();
      case VALIDATE -> requirePassed(validator.validate(), null);
      case MIGRATE -> migrate();
    }
  }

  /** What the last {@link #initialize()} found. */
  public SchemaStatus status() {
    return status;
  }

  private void checkTablesExist() {
    List<String> missing = missingTables();
    if (!missing.isEmpty() && policy == SchemaInitialization.ALWAYS) {
      if (missing.size() != TABLES.size()) {
        throw new SagaForgeConfigurationException(
            "Saga Forge's schema is incomplete: tables "
                + missing
                + " are missing while others exist.",
            "Apply the missing Saga Forge migrations (initialize-schema=migrate) or the matching"
                + " schema-upgrade-NNN.sql scripts of saga-forge-storage-postgres; initialize-schema"
                + "=always only creates the schema in a database without Saga Forge's tables.");
      }
      new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource);
    } else if (!missing.isEmpty()) {
      throw new SagaForgeConfigurationException(
          "Saga Forge's tables " + missing + " do not exist in the database.",
          "Set saga-forge.storage.postgres.initialize-schema=migrate to apply Saga Forge's"
              + " migrations at startup, apply classpath:"
              + PostgresSchema.MIGRATION_LOCATION.substring("classpath:".length())
              + " yourself, or set initialize-schema=always to create them from "
              + SCHEMA
              + ".");
    }
    SchemaValidationReport report = validator.validate();
    status = new SchemaStatus(policy, null, report);
    if (!report.passed() || !report.warnings().isEmpty()) {
      LOG.log(Level.WARNING, report.describe());
    }
  }

  private void migrate() {
    if (migrator == null) {
      throw flywayMissing(missingFlywayArtifacts(classLoader));
    }
    Optional<SchemaVersion> applied = validator.appliedVersion(migration.getHistoryTable());
    SchemaVersion baseline = applied.isPresent() ? null : baseline();
    if (baseline != null) {
      LOG.log(
          Level.INFO,
          "Saga Forge takes over an existing schema at {0}; {1} records it as the baseline",
          baseline,
          migration.getHistoryTable());
    }
    SchemaVersion version = migrator.migrate(baseline);
    requirePassed(validator.validate(), version);
  }

  private SchemaVersion baseline() {
    Integer configured = migration.getBaselineVersion();
    if (configured != null) {
      try {
        return SchemaVersion.of(configured);
      } catch (IllegalArgumentException e) {
        throw new SagaForgeConfigurationException(
            "saga-forge.storage.postgres.migration.baseline-version="
                + configured
                + ": "
                + e.getMessage(),
            "Set it to the version the database is at, or remove it to detect the version.");
      }
    }
    return switch (validator.detectVersion()) {
      case SchemaDetection.Empty empty -> null;
      case SchemaDetection.Matched matched -> matched.version();
      case SchemaDetection.Unmatched unmatched -> throw SagaSchemaException.unmatched(unmatched);
    };
  }

  private void requirePassed(SchemaValidationReport report, SchemaVersion version) {
    status = new SchemaStatus(policy, version, report);
    if (!report.passed()) {
      throw SagaSchemaException.failedCheck(report);
    }
    if (!report.warnings().isEmpty()) {
      LOG.log(Level.WARNING, report.describe());
    }
  }

  /** The {@code migrate} failure when Flyway, or its Postgres support, is not on the class path. */
  static SagaForgeConfigurationException flywayMissing(String artifacts) {
    return new SagaForgeConfigurationException(
        "saga-forge.storage.postgres.initialize-schema=migrate needs Flyway, but "
            + artifacts
            + " is not on the class path.",
        "Add the missing dependency, or use initialize-schema=validate and apply the migrations at "
            + PostgresSchema.MIGRATION_LOCATION
            + " yourself.");
  }

  static String missingFlywayArtifacts(ClassLoader classLoader) {
    List<String> missing = new ArrayList<>();
    if (!ClassUtils.isPresent("org.flywaydb.core.Flyway", classLoader)) {
      missing.add(FLYWAY_CORE);
    }
    if (!ClassUtils.isPresent(FlywaySchemaMigrator.POSTGRES_SUPPORT, classLoader)) {
      missing.add(FLYWAY_POSTGRES);
    }
    // Both present, but no migrator bean (built without one): name both, as the contract does.
    return String.join(
        " and ", missing.isEmpty() ? List.of(FLYWAY_CORE, FLYWAY_POSTGRES) : missing);
  }

  private List<String> missingTables() {
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData meta = connection.getMetaData();
      List<String> missing = new ArrayList<>();
      for (String table : TABLES) {
        if (!exists(meta, connection.getSchema(), table)) {
          missing.add(table);
        }
      }
      return missing;
    } catch (SQLException e) {
      throw new SagaForgeConfigurationException(
          "Saga Forge could not inspect the database schema: " + e.getMessage(),
          "Check the DataSource configuration (spring.datasource.*).");
    }
  }

  private static boolean exists(DatabaseMetaData meta, String schema, String table)
      throws SQLException {
    try (ResultSet tables =
        meta.getTables(null, schema, table.toLowerCase(Locale.ROOT), new String[] {"TABLE"})) {
      return tables.next();
    }
  }
}
