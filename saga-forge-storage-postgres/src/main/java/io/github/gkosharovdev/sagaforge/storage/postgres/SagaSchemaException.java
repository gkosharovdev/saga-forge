package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.Objects;

/**
 * The database's Saga Forge schema cannot be used: a check found failing differences, or the tables
 * exist but match no released version. The message is {@link SchemaValidationReport#describe()}.
 */
public class SagaSchemaException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient SchemaValidationReport report;
  private final boolean unmatched;

  private SagaSchemaException(String prefix, SchemaValidationReport report, boolean unmatched) {
    super(prefix + Objects.requireNonNull(report, "report").describe());
    this.report = report;
    this.unmatched = unmatched;
  }

  /**
   * @param report a report that did not pass
   * @return an exception for a database that differs from the version it should be at
   */
  public static SagaSchemaException failedCheck(SchemaValidationReport report) {
    return new SagaSchemaException("", report, false);
  }

  /**
   * @param detection the detection that matched no version
   * @return an exception for tables that match no released version, carrying the closest report
   */
  public static SagaSchemaException unmatched(SchemaDetection.Unmatched detection) {
    return new SagaSchemaException(
        "Saga Forge's tables exist but match no released schema version. Closest: "
            + detection.closest().expected()
            + ". ",
        detection.closest(),
        true);
  }

  /**
   * @return the report behind the failure
   */
  public SchemaValidationReport report() {
    return report;
  }

  /**
   * @return {@code true} when the tables match no released version (detection), {@code false} when
   *     a check against a known version failed
   */
  public boolean unmatched() {
    return unmatched;
  }
}
