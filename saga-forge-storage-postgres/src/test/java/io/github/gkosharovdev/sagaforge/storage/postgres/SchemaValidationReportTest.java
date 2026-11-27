package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.ObjectKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.Problem;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.Severity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaValidationReportTest {

  private static final SchemaVersion V5 = SchemaVersion.of(5);

  @Test
  @DisplayName("describe() lists failures first, then warnings, in the contract's format")
  void describe() {
    SchemaValidationReport report =
        new SchemaValidationReport(
            V5,
            List.of(
                SchemaDifference.missing(
                    "saga_instance",
                    ObjectKind.INDEX,
                    "USING btree (status, last_modified_at, saga_id)",
                    "index"),
                SchemaDifference.different(
                    "saga_instance",
                    ObjectKind.COLUMN,
                    "compensation_round",
                    "integer NOT NULL",
                    "bigint NOT NULL"),
                SchemaDifference.missing(
                    "saga_instance", ObjectKind.COLUMN, "resolution", "jsonb NULL")));

    assertThat(report.passed()).isFalse();
    assertThat(report.failures()).hasSize(2);
    assertThat(report.warnings()).hasSize(1);
    assertThat(report.describe())
        .isEqualTo(
            """
            Saga Forge's schema does not match V5 (saga operations): 2 failures, 1 warning.
              FAILURE saga_instance: column compensation_round is different (expected integer NOT NULL, found bigint NOT NULL)
              FAILURE saga_instance: column resolution is missing (expected jsonb NULL)
              WARNING saga_instance: index USING btree (status, last_modified_at, saga_id) is missing (expected index)""");
  }

  @Test
  @DisplayName("a report with only warnings passes")
  void warningsPass() {
    SchemaValidationReport report =
        new SchemaValidationReport(
            V5,
            List.of(SchemaDifference.missing("t", ObjectKind.INDEX, "USING btree (a)", "index")));
    assertThat(report.passed()).isTrue();
    assertThat(report.describe())
        .startsWith("Saga Forge's schema matches V5 (saga operations): 0 failures, 1 warning.");
    assertThat(new SchemaValidationReport(V5, List.of()).describe())
        .isEqualTo("Saga Forge's schema matches V5 (saga operations): 0 failures, 0 warnings.");
  }

  @Test
  @DisplayName("severity follows the kind and found is null exactly when missing")
  void invariants() {
    assertThatThrownBy(
            () ->
                new SchemaDifference(
                    "t", ObjectKind.INDEX, "i", Problem.MISSING, "index", null, Severity.FAILURE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SchemaDifference(
                    "t", ObjectKind.COLUMN, "c", Problem.MISSING, "text", "text", Severity.FAILURE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
