package io.github.gkosharovdev.sagaforge.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link CommandFailure}: the transport's report of a failed outbox command (012 data-model.md).
 */
class CommandFailureTest {

  private static final CommandId SENT =
      CommandId.of("saga-1", "capturePayment", "com.example.CapturePayment", 1);
  private static final String CAUSE = "com.example.CardDeclined";

  @Test
  @DisplayName("derives the instance, the sender and the command type from the command id")
  void derivedValues() {
    CommandFailure report = new CommandFailure(SENT, FailureClassification.FINAL, CAUSE, 1);

    assertThat(report.sagaId()).isEqualTo("saga-1");
    assertThat(report.senderName()).isEqualTo("capturePayment");
    assertThat(report.commandType()).isEqualTo("com.example.CapturePayment");
  }

  @Test
  @DisplayName("two reports of the same failed delivery are equal")
  void valueEquality() {
    assertThat(new CommandFailure(SENT, FailureClassification.EXHAUSTED, CAUSE, 10))
        .isEqualTo(new CommandFailure(SENT, FailureClassification.EXHAUSTED, CAUSE, 10))
        .isNotEqualTo(new CommandFailure(SENT, FailureClassification.EXHAUSTED, CAUSE, 9));
  }

  @Test
  @DisplayName("rejects a missing or unscoped command id")
  void commandIdRules() {
    assertThatThrownBy(() -> new CommandFailure(null, FailureClassification.FINAL, CAUSE, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandId must not be null");
    assertThatThrownBy(
            () ->
                new CommandFailure(
                    CommandId.unscoped("com.example.Notify"),
                    FailureClassification.FINAL,
                    CAUSE,
                    1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be saga-scoped");
  }

  @ParameterizedTest
  @EnumSource(
      value = FailureClassification.class,
      names = {"TRANSIENT", "UNCLASSIFIED"})
  @DisplayName("accepts only FINAL and EXHAUSTED")
  void classificationRule(FailureClassification classification) {
    assertThatThrownBy(() -> new CommandFailure(SENT, classification, CAUSE, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FINAL or EXHAUSTED");
    assertThatThrownBy(() -> new CommandFailure(SENT, null, CAUSE, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FINAL or EXHAUSTED");
  }

  @Test
  @DisplayName("rejects a blank cause type and fewer than one attempt")
  void causeAndAttempts() {
    assertThatThrownBy(() -> new CommandFailure(SENT, FailureClassification.FINAL, " ", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("causeType");
    assertThatThrownBy(() -> new CommandFailure(SENT, FailureClassification.FINAL, CAUSE, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attempts must be at least 1");
  }
}
