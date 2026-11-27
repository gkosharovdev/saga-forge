package io.github.gkosharovdev.sagaforge.spring.storage;

import static io.github.gkosharovdev.sagaforge.spring.storage.MigrationSchemas.SAGA_FORGE_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.Migration;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.SchemaInitialization;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchemaValidator;
import io.github.gkosharovdev.sagaforge.storage.postgres.SagaSchemaException;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaVersion;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link SchemaInitializer}'s dispatch per mode ({@code specs/018-versioned-schema-migrations}
 * T023, T027, T030), with a recording {@link SchemaMigrator} over the real {@link
 * FlywaySchemaMigrator} and the real validator, each test in a fresh schema.
 */
@Tag("container")
class SchemaInitializerTest {

  private String schema;
  private DataSource dataSource;
  private RecordingMigrator migrator;

  @BeforeEach
  void freshSchema() {
    schema = MigrationSchemas.create();
    dataSource = MigrationSchemas.dataSource(schema);
    migrator =
        new RecordingMigrator(
            new FlywaySchemaMigrator(dataSource, SAGA_FORGE_HISTORY, getClass().getClassLoader()));
  }

  private SchemaInitializer initializer(SchemaInitialization mode, Migration migration) {
    return new SchemaInitializer(
        dataSource,
        mode,
        new PostgresSchemaValidator(dataSource),
        migrator,
        migration,
        getClass().getClassLoader());
  }

  private SchemaInitializer migrating() {
    return initializer(SchemaInitialization.MIGRATE, new Migration());
  }

  @Test
  @DisplayName("migrate, on an empty schema: applies V1 to V6 with no baseline and passes")
  void migrateEmpty() {
    SchemaInitializer initializer = migrating();
    initializer.initialize();

    assertThat(migrator.baselines).containsExactly((SchemaVersion) null);
    assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
        .containsExactly("1", "2", "3", "4", "5", "6");
    assertThat(initializer.status().version()).isEqualTo(PostgresSchema.currentVersion());
    assertThat(initializer.status().report().passed()).isTrue();
  }

  @Test
  @DisplayName("migrate, a second time: finds the history, applies nothing and passes")
  void migrateAgain() {
    migrating().initialize();
    migrating().initialize();

    assertThat(migrator.baselines).containsExactly(null, null);
    assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
        .containsExactly("1", "2", "3", "4", "5", "6");
  }

  @Test
  @DisplayName("migrate, beside application tables and no Saga Forge tables: baselines at 0")
  void migrateBesideApplicationTables() {
    MigrationSchemas.jdbc(schema).execute("CREATE TABLE app_table (id INT)");

    migrating().initialize();

    assertThat(MigrationSchemas.types(schema, SAGA_FORGE_HISTORY))
        .containsExactly("BASELINE", "SQL", "SQL", "SQL", "SQL", "SQL", "SQL");
    assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
        .containsExactly("0", "1", "2", "3", "4", "5", "6");
  }

  @Test
  @DisplayName("migrate, on a schema.sql database: detects V6 and takes it as the baseline")
  void migrateDetectsCurrentSchema() {
    MigrationSchemas.run(schema, "/" + PostgresSchema.SCHEMA_SCRIPT);

    migrating().initialize();

    assertThat(migrator.baselines).containsExactly(PostgresSchema.currentVersion());
    assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY)).containsExactly("6");
  }

  @Test
  @DisplayName("migrate, on a released 0.1.0 database: baselines at V1 and applies V2 to V6")
  void migrateDetectsReleasedLevel() {
    MigrationSchemas.run(schema, "/takeover/schema-0.1.0.sql");

    migrating().initialize();

    assertThat(migrator.baselines).containsExactly(SchemaVersion.of(1));
    assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
        .containsExactly("1", "2", "3", "4", "5", "6");
  }

  @Test
  @DisplayName("migrate with baseline-version: skips detection")
  void migrateConfiguredBaseline() {
    MigrationSchemas.run(schema, "/takeover/schema-0.4.0.sql");
    // Unmatched by detection: V4's leader table lacks a column.
    MigrationSchemas.jdbc(schema)
        .execute("ALTER TABLE saga_message_store_leader DROP COLUMN holder_host");
    Migration migration = new Migration();
    migration.setBaselineVersion(4);

    assertThatThrownBy(() -> initializer(SchemaInitialization.MIGRATE, migration).initialize())
        .isInstanceOf(SagaSchemaException.class)
        .hasMessageContaining("holder_host");
    // The baseline came from the property: V5 and V6 were applied over it, then the check failed.
    assertThat(migrator.baselines).containsExactly(SchemaVersion.of(4));
    assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
        .containsExactly("4", "5", "6");
  }

  @Test
  @DisplayName("migrate with an unknown baseline-version: fails naming the known range")
  void migrateUnknownBaseline() {
    Migration migration = new Migration();
    migration.setBaselineVersion(9);

    assertThatThrownBy(() -> initializer(SchemaInitialization.MIGRATE, migration).initialize())
        .isInstanceOf(SagaForgeConfigurationException.class)
        .hasMessageContaining("baseline-version=9")
        .hasMessageContaining("1 to 6");
    assertThat(migrator.baselines).isEmpty();
  }

  @Test
  @DisplayName("migrate, on a database matching no version: fails before any write")
  void migrateUnmatched() {
    MigrationSchemas.run(schema, "/takeover/schema-0.4.0.sql");
    MigrationSchemas.jdbc(schema).execute("ALTER TABLE saga_instance DROP COLUMN outcome");

    assertThatThrownBy(() -> migrating().initialize())
        .isInstanceOf(SagaSchemaException.class)
        .hasMessageContaining("match no released schema version")
        .hasMessageContaining("Closest: V4")
        .hasMessageContaining("outcome")
        .satisfies(e -> assertThat(((SagaSchemaException) e).unmatched()).isTrue());
    assertThat(migrator.baselines).isEmpty();
    assertThat(MigrationSchemas.exists(schema, SAGA_FORGE_HISTORY)).isFalse();
  }

  @Test
  @DisplayName("migrate without a migrator: fails naming the Flyway artifacts")
  void migrateWithoutMigrator() {
    assertThatThrownBy(
            () ->
                new SchemaInitializer(
                        dataSource,
                        SchemaInitialization.MIGRATE,
                        new PostgresSchemaValidator(dataSource),
                        null,
                        new Migration(),
                        getClass().getClassLoader())
                    .initialize())
        .isInstanceOf(SagaForgeConfigurationException.class)
        .hasMessageContaining("initialize-schema=migrate needs Flyway")
        .hasMessageContaining(SchemaInitializer.FLYWAY_CORE)
        .hasMessageContaining(SchemaInitializer.FLYWAY_POSTGRES);
  }

  @Test
  @DisplayName("validate, on a migrated schema: passes and writes nothing")
  void validatePasses() {
    migrating().initialize();
    int rows = MigrationSchemas.count(schema, SAGA_FORGE_HISTORY);

    SchemaInitializer initializer = initializer(SchemaInitialization.VALIDATE, new Migration());
    initializer.initialize();

    assertThat(initializer.status().report().passed()).isTrue();
    assertThat(MigrationSchemas.count(schema, SAGA_FORGE_HISTORY)).isEqualTo(rows);
  }

  @Test
  @DisplayName("validate, with a dropped column: fails naming table and column")
  void validateFails() {
    MigrationSchemas.run(schema, "/" + PostgresSchema.SCHEMA_SCRIPT);
    MigrationSchemas.jdbc(schema).execute("ALTER TABLE saga_context DROP COLUMN last_modified_at");

    assertThatThrownBy(
            () -> initializer(SchemaInitialization.VALIDATE, new Migration()).initialize())
        .isInstanceOf(SagaSchemaException.class)
        .hasMessageContaining("saga_context: column last_modified_at is missing")
        .satisfies(e -> assertThat(((SagaSchemaException) e).unmatched()).isFalse());
    assertThat(MigrationSchemas.exists(schema, SAGA_FORGE_HISTORY)).isFalse();
  }

  @Test
  @DisplayName("never, with a dropped column: starts and keeps the failed report as status")
  void neverReports() {
    MigrationSchemas.run(schema, "/" + PostgresSchema.SCHEMA_SCRIPT);
    MigrationSchemas.jdbc(schema).execute("ALTER TABLE saga_context DROP COLUMN last_modified_at");

    SchemaInitializer initializer = initializer(SchemaInitialization.NEVER, new Migration());
    initializer.initialize();

    assertThat(initializer.status().report().passed()).isFalse();
    assertThat(initializer.status().report().describe()).contains("last_modified_at");
  }

  /** Records each baseline and delegates. */
  private static final class RecordingMigrator implements SchemaMigrator {

    private final SchemaMigrator delegate;
    private final List<SchemaVersion> baselines = new ArrayList<>();

    RecordingMigrator(SchemaMigrator delegate) {
      this.delegate = delegate;
    }

    @Override
    public SchemaVersion migrate(SchemaVersion baseline) {
      baselines.add(baseline);
      return delegate.migrate(baseline);
    }
  }
}
