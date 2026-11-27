package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.Objects;

/**
 * Which {@link SchemaVersion} a database without Saga Forge's migration history is at, found from
 * its shape by {@link PostgresSchemaValidator#detectVersion()}.
 */
public sealed interface SchemaDetection
    permits SchemaDetection.Empty, SchemaDetection.Matched, SchemaDetection.Unmatched {

  /** None of Saga Forge's tables exist. */
  record Empty() implements SchemaDetection {}

  /**
   * The newest version the database fully matches; warnings are allowed.
   *
   * @param version the matched version
   * @param report the check against it
   */
  record Matched(SchemaVersion version, SchemaValidationReport report) implements SchemaDetection {

    /** Validates the components. */
    public Matched {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(report, "report");
    }
  }

  /**
   * Saga Forge's tables exist but match no version.
   *
   * @param closest the check against the version with the fewest failures (the newer on a tie)
   */
  record Unmatched(SchemaValidationReport closest) implements SchemaDetection {

    /** Validates the component. */
    public Unmatched {
      Objects.requireNonNull(closest, "closest");
    }
  }
}
