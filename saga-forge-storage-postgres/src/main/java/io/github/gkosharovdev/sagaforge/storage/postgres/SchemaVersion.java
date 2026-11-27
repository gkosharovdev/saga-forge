package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.Objects;

/**
 * One version of Saga Forge's Postgres schema: the migration {@code V<number>__<words>.sql} under
 * {@link PostgresSchema#MIGRATION_LOCATION}, and the schema a database has once every version up to
 * it is applied. Ordered by number.
 *
 * @param number the version number, at least 1
 * @param description what the version adds, for example {@code "saga operations"}; non-blank
 */
public record SchemaVersion(int number, String description) implements Comparable<SchemaVersion> {

  /** Validates the components. */
  public SchemaVersion {
    if (number < 1) {
      throw new IllegalArgumentException("a schema version number is at least 1, was " + number);
    }
    Objects.requireNonNull(description, "description");
    if (description.isBlank()) {
      throw new IllegalArgumentException("a schema version needs a description");
    }
  }

  /**
   * @param number a version number this jar ships
   * @return that version
   * @throws IllegalArgumentException if this jar ships no version with that number, naming the
   *     known range
   */
  public static SchemaVersion of(int number) {
    return PostgresSchema.versions().stream()
        .filter(version -> version.number() == number)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Saga Forge ships no schema version "
                        + number
                        + "; the known versions are 1 to "
                        + PostgresSchema.currentVersion().number()));
  }

  @Override
  public int compareTo(SchemaVersion other) {
    return Integer.compare(number, other.number);
  }

  /**
   * @return for example {@code "V5 (saga operations)"}
   */
  @Override
  public String toString() {
    return "V" + number + " (" + description + ")";
  }
}
