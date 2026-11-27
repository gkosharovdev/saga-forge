package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema;
import io.github.gkosharovdev.sagaforge.storage.postgres.SagaSchemaException;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDetection;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.ObjectKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaValidationReport;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaVersion;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * {@link SagaSchemaFailureAnalyzer}'s two texts ({@code specs/018-versioned-schema-migrations}
 * T033).
 */
class SagaSchemaFailureAnalyzerTest {

  private static final SchemaValidationReport MISSING_COLUMN =
      new SchemaValidationReport(
          PostgresSchema.currentVersion(),
          List.of(
              SchemaDifference.missing(
                  "saga_context",
                  ObjectKind.COLUMN,
                  "last_modified_at",
                  "timestamp with time zone NOT NULL")));

  private final SagaSchemaFailureAnalyzer analyzer = new SagaSchemaFailureAnalyzer();

  @Test
  @DisplayName("a failed check: the report as description, apply or repair as action")
  void failedCheck() {
    FailureAnalysis analysis =
        analyzer.analyze(
            new BeanCreationException("schema", SagaSchemaException.failedCheck(MISSING_COLUMN)));

    assertThat(analysis.getDescription())
        .contains("does not match V5 (saga operations)")
        .contains("saga_context: column last_modified_at is missing");
    assertThat(analysis.getAction()).isEqualTo(SagaSchemaFailureAnalyzer.CHECK_FAILED_ACTION);
  }

  @Test
  @DisplayName("an unmatched schema: names the closest version, baseline-version as action")
  void unmatched() {
    SchemaValidationReport closest =
        new SchemaValidationReport(SchemaVersion.of(4), MISSING_COLUMN.differences());

    FailureAnalysis analysis =
        analyzer.analyze(SagaSchemaException.unmatched(new SchemaDetection.Unmatched(closest)));

    assertThat(analysis.getDescription())
        .contains("match no released schema version")
        .contains("Closest: V4")
        .contains("last_modified_at");
    assertThat(analysis.getAction())
        .contains("saga-forge.storage.postgres.migration.baseline-version");
  }

  @Test
  @DisplayName("any other failure: not analysed")
  void otherFailure() {
    assertThat(analyzer.analyze(new IllegalStateException("other"))).isNull();
  }
}
