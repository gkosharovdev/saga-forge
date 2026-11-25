package io.github.gkosharovdev.sagaforge.core.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code COMMAND_FAILED} trigger of {@code specs/012-outbox-command-failure-feedback} (data-model.md); the
 * earlier kinds are covered by {@code CompensationVocabularyTest}.
 */
class CompensationTriggerTest {

    private static final Instant AT = Instant.parse("2026-11-24T12:00:00Z");
    private static final String COMMAND = "com.example.CapturePayment";
    private static final CommandFailure REPORT = new CommandFailure(
            CommandId.of("saga-1", "capturePayment", COMMAND, 1), FailureClassification.EXHAUSTED,
            "com.example.GatewayTimeout", 10);

    private static SagaFailure failure(String step, FailureClassification classification) {
        return new SagaFailure(step, "com.example.Cause", null, AT, classification, 1);
    }

    @Test
    @DisplayName("commandFailed names the sending step and command type and records no message")
    void commandFailed() {
        CompensationTrigger trigger = CompensationTrigger.commandFailed(REPORT, AT);

        assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.COMMAND_FAILED);
        assertThat(trigger.stepName()).isEqualTo("capturePayment");
        assertThat(trigger.commandType()).isEqualTo(COMMAND);
        assertThat(trigger.eventType()).isNull();
        assertThat(trigger.failure()).isEqualTo(new SagaFailure("capturePayment", "com.example.GatewayTimeout", null,
                AT, FailureClassification.EXHAUSTED, 10));
        assertThatThrownBy(() -> CompensationTrigger.commandFailed(null, AT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("report");
    }

    @Test
    @DisplayName("COMMAND_FAILED accepts FINAL and EXHAUSTED failures of the sending step only")
    void commandFailedRules() {
        CompensationTriggerKind kind = CompensationTriggerKind.COMMAND_FAILED;
        assertThat(new CompensationTrigger(kind, "pay", null, COMMAND, failure("pay", FailureClassification.FINAL),
                AT).failure().classification()).isEqualTo(FailureClassification.FINAL);

        assertThatThrownBy(() -> new CompensationTrigger(kind, "pay", null, COMMAND,
                failure("pay", FailureClassification.UNCLASSIFIED), AT))
                .hasMessageContaining("must be classified FINAL or EXHAUSTED");
        assertThatThrownBy(() -> new CompensationTrigger(kind, "pay", null, COMMAND,
                failure("other", FailureClassification.FINAL), AT)).hasMessageContaining("must be the failed step");
        assertThatThrownBy(() -> new CompensationTrigger(kind, "pay", null, COMMAND, null, AT))
                .hasMessageContaining("failure must be non-null if and only if");
        assertThatThrownBy(() -> new CompensationTrigger(kind, "pay", "com.example.Event", COMMAND,
                failure("pay", FailureClassification.FINAL), AT))
                .hasMessageContaining("eventType must be non-null if and only if");
    }

    @Test
    @DisplayName("commandType is present if and only if the kind is COMMAND_FAILED")
    void commandTypeIffCommandFailed() {
        assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.COMMAND_FAILED, "pay", null, null,
                failure("pay", FailureClassification.FINAL), AT))
                .hasMessageContaining("commandType must be non-null if and only if kind is COMMAND_FAILED");
        assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.COMMAND_FAILED, "pay", null, " ",
                failure("pay", FailureClassification.FINAL), AT)).hasMessageContaining("commandType must not be blank");
        assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FINAL_FAILURE, "pay", null, COMMAND,
                failure("pay", FailureClassification.FINAL), AT))
                .hasMessageContaining("commandType must be non-null if and only if kind is COMMAND_FAILED");
    }

    @Test
    @DisplayName("the constructor without a command type keeps working for the earlier kinds")
    void earlierConstructor() {
        CompensationTrigger trigger = new CompensationTrigger(CompensationTriggerKind.FINAL_FAILURE, "pay", null,
                failure("pay", FailureClassification.FINAL), AT);

        assertThat(trigger.commandType()).isNull();
        assertThat(trigger).isEqualTo(CompensationTrigger.failed(failure("pay", FailureClassification.FINAL), AT));
        assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.COMMAND_FAILED, "pay", null,
                failure("pay", FailureClassification.FINAL), AT)).hasMessageContaining("commandType");
    }
}
