package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.List;

/**
 * Where Saga Forge's Postgres schema lives on the class path, and which versions this jar ships.
 *
 * <p>The schema ships twice. As versioned migrations under {@link #MIGRATION_LOCATION}, for Flyway,
 * recorded in Saga Forge's own history table ({@link #DEFAULT_HISTORY_TABLE}) so that their numbers
 * never clash with the application's. And as {@link #SCHEMA_SCRIPT} plus one {@code
 * schema-upgrade-NNN.sql} per change, for hosts that apply scripts themselves. Both end at the same
 * schema. {@link PostgresSchemaValidator} checks a database against a version.
 *
 * <p>A host running its own Flyway points it at {@link #MIGRATION_LOCATION} with {@code
 * table(DEFAULT_HISTORY_TABLE)} and {@code placeholderReplacement(false)}. The application's own
 * Flyway must never list that location.
 */
public final class PostgresSchema {

  /** The Flyway location of Saga Forge's versioned migrations. */
  public static final String MIGRATION_LOCATION =
      "classpath:io/github/gkosharovdev/sagaforge/storage/postgres/migration";

  /** The default name of Saga Forge's Flyway history table. */
  public static final String DEFAULT_HISTORY_TABLE = "saga_forge_schema_history";

  /** The current schema as one script, on the class path. */
  public static final String SCHEMA_SCRIPT =
      "io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql";

  private static final List<SchemaVersion> VERSIONS =
      List.of(
          new SchemaVersion(1, "base schema"),
          new SchemaVersion(2, "saga outcome events"),
          new SchemaVersion(3, "saga deadlines"),
          new SchemaVersion(4, "active standby relay"),
          new SchemaVersion(5, "saga operations"),
          new SchemaVersion(6, "process metadata"));

  private PostgresSchema() {}

  /**
   * @return every version this jar ships, oldest first; one per migration file
   */
  public static List<SchemaVersion> versions() {
    return VERSIONS;
  }

  /**
   * @return the newest version this jar ships, the one this release's storage needs
   */
  public static SchemaVersion currentVersion() {
    return VERSIONS.getLast();
  }
}
