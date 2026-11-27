package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import io.github.gkosharovdev.sagaforge.storage.postgres.SagaSchemaException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.util.ClassUtils;

/**
 * Explains a failed schema check or an unrecognised schema ({@code
 * saga-forge.storage.postgres.initialize-schema=validate} or {@code migrate}, {@code
 * specs/018-versioned-schema-migrations} R9). The Postgres module is optional in the starter, so
 * the exception type is only touched once it is known to be on the class path.
 */
public class SagaSchemaFailureAnalyzer implements FailureAnalyzer {

  private static final String EXCEPTION =
      "io.github.gkosharovdev.sagaforge.storage.postgres.SagaSchemaException";

  static final String CHECK_FAILED_ACTION =
      "Apply the missing Saga Forge migrations (initialize-schema=migrate) or repair the listed"
          + " differences.";
  static final String UNMATCHED_ACTION =
      "Repair the listed differences, or set"
          + " saga-forge.storage.postgres.migration.baseline-version to the version the database is"
          + " at.";

  @Override
  public FailureAnalysis analyze(Throwable failure) {
    if (!ClassUtils.isPresent(EXCEPTION, SagaSchemaFailureAnalyzer.class.getClassLoader())) {
      return null;
    }
    return Postgres.analyze(failure);
  }

  /** Loaded only with the Postgres module present. */
  private static final class Postgres {

    static FailureAnalysis analyze(Throwable failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof SagaSchemaException schema) {
          return new FailureAnalysis(
              schema.getMessage(),
              schema.unmatched() ? UNMATCHED_ACTION : CHECK_FAILED_ACTION,
              schema);
        }
      }
      return null;
    }
  }
}
