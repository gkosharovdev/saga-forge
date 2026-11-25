package io.github.gkosharovdev.sagaforge.core.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The outcome event records of specs/011-saga-outcome-events data-model.md "Validation". */
class OutcomeTypesTest {

  private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");
  private static final Map<String, String> ASSOCIATIONS = Map.of("orderId", "order-1");
  private static final OutcomeTrigger REJECTED =
      new OutcomeTrigger(
          CompensationTriggerKind.FAILURE_OUTCOME, "rejectOrder", null, null, null, AT);

  @Nested
  class Identity {

    @Test
    void eachFactoryDerivesTheEventIdFromTheTerminalStatus() {
      assertThat(SagaCompleted.of("Saga", "saga-1", ASSOCIATIONS, AT, "completeOrder").eventId())
          .isEqualTo("saga-1:COMPLETED");
      assertThat(
              SagaFailed.inStep(
                      "Saga",
                      "saga-1",
                      ASSOCIATIONS,
                      AT,
                      "processPayment",
                      FailureClassification.FINAL,
                      "java.lang.IllegalStateException",
                      1)
                  .eventId())
          .isEqualTo("saga-1:FAILED");
      assertThat(
              SagaFailed.inCompensation(
                      "Saga",
                      "saga-1",
                      ASSOCIATIONS,
                      AT,
                      "processPayment",
                      FailureClassification.FINAL,
                      "java.lang.IllegalStateException",
                      1,
                      REJECTED)
                  .eventId())
          .isEqualTo("saga-1:COMPENSATION_FAILED");
      assertThat(SagaCompensated.of("Saga", "saga-1", ASSOCIATIONS, AT, REJECTED).eventId())
          .isEqualTo("saga-1:COMPENSATED");
    }

    @Test
    void equalOutcomesAreEqual() {
      assertThat(SagaCompleted.of("Saga", "saga-1", ASSOCIATIONS, AT, "completeOrder"))
          .isEqualTo(
              SagaCompleted.of("Saga", "saga-1", new HashMap<>(ASSOCIATIONS), AT, "completeOrder"));
    }
  }

  @Nested
  class Associations {

    @Test
    void areCopiedAndUnmodifiable() {
      Map<String, String> source = new HashMap<>(ASSOCIATIONS);
      SagaCompleted completed = SagaCompleted.of("Saga", "saga-1", source, AT, "completeOrder");
      source.put("other", "x");

      assertThat(completed.associations()).containsExactlyEntriesOf(ASSOCIATIONS);
      assertThatThrownBy(() -> completed.associations().put("other", "x"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mustNotBeEmpty() {
      assertThatThrownBy(() -> SagaCompleted.of("Saga", "saga-1", Map.of(), AT, "completeOrder"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("associations");
    }
  }

  @Nested
  class RequiredComponents {

    @Test
    void areNamedWhenMissing() {
      assertThatThrownBy(() -> SagaCompleted.of(" ", "saga-1", ASSOCIATIONS, AT, "completeOrder"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sagaType");
      assertThatThrownBy(
              () -> SagaCompleted.of("Saga", "saga-1", ASSOCIATIONS, null, "completeOrder"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("occurredAt");
      assertThatThrownBy(() -> SagaCompleted.of("Saga", "saga-1", ASSOCIATIONS, AT, ""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("outcomeStep");
      assertThatThrownBy(() -> SagaCompensated.of("Saga", "saga-1", ASSOCIATIONS, AT, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("trigger");
      assertThatThrownBy(
              () ->
                  SagaFailed.inStep(
                      "Saga",
                      "saga-1",
                      ASSOCIATIONS,
                      AT,
                      "step",
                      null,
                      "java.lang.IllegalStateException",
                      1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("classification");
      assertThatThrownBy(
              () ->
                  SagaFailed.inStep(
                      "Saga",
                      "saga-1",
                      ASSOCIATIONS,
                      AT,
                      "step",
                      FailureClassification.FINAL,
                      "java.lang.IllegalStateException",
                      0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("attempts");
    }
  }

  @Nested
  class FailedPhase {

    @Test
    void aStepFailureCarriesNoTrigger() {
      assertThatThrownBy(
              () ->
                  new SagaFailed(
                      "saga-1:FAILED",
                      "Saga",
                      "saga-1",
                      ASSOCIATIONS,
                      AT,
                      FailurePhase.STEP,
                      "step",
                      FailureClassification.FINAL,
                      "java.lang.IllegalStateException",
                      1,
                      REJECTED))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("compensationTrigger");
    }

    @Test
    void aCompensationFailureCarriesItsTrigger() {
      assertThatThrownBy(
              () ->
                  new SagaFailed(
                      "saga-1:COMPENSATION_FAILED",
                      "Saga",
                      "saga-1",
                      ASSOCIATIONS,
                      AT,
                      FailurePhase.COMPENSATION,
                      "step",
                      FailureClassification.FINAL,
                      "java.lang.IllegalStateException",
                      1,
                      null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("compensationTrigger");
    }
  }

  @Nested
  class Trigger {

    @Test
    void ofAFailureKeepsItsFactsAndDropsTheMessage() {
      SagaFailure failure =
          SagaFailure.of(
              "processPayment",
              new IllegalStateException("card 4111 declined"),
              AT,
              FailureClassification.FINAL,
              2);
      OutcomeTrigger trigger = OutcomeTrigger.of(CompensationTrigger.failed(failure, AT));

      assertThat(trigger)
          .isEqualTo(
              new OutcomeTrigger(
                  CompensationTriggerKind.FINAL_FAILURE,
                  "processPayment",
                  FailureClassification.FINAL,
                  "java.lang.IllegalStateException",
                  2,
                  AT));
      assertThat(trigger.toString()).doesNotContain("4111");
    }

    @Test
    void ofAFailureOutcomeHasNoFailureFacts() {
      OutcomeTrigger trigger =
          OutcomeTrigger.of(
              CompensationTrigger.failureOutcome(
                  "rejectOrder", "com.example.StockUnavailableEvent", AT));

      assertThat(trigger).isEqualTo(REJECTED);
    }

    @Test
    void ofACommandFailureCarriesTheCommandType() {
      CommandFailure report =
          new CommandFailure(
              CommandId.of("saga-1", "processPayment", "com.example.CapturePayment", 1),
              FailureClassification.FINAL,
              "com.example.Declined",
              1);
      OutcomeTrigger trigger = OutcomeTrigger.of(CompensationTrigger.commandFailed(report, AT));

      assertThat(trigger)
          .isEqualTo(
              new OutcomeTrigger(
                  CompensationTriggerKind.COMMAND_FAILED,
                  "processPayment",
                  FailureClassification.FINAL,
                  "com.example.Declined",
                  1,
                  AT,
                  "com.example.CapturePayment"));
    }

    @Test
    void commandTypeIsPresentOnlyForACommandFailure() {
      assertThatThrownBy(
              () ->
                  new OutcomeTrigger(
                      CompensationTriggerKind.COMMAND_FAILED,
                      "step",
                      FailureClassification.FINAL,
                      "com.example.Declined",
                      1,
                      AT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("commandType");
      assertThatThrownBy(
              () ->
                  new OutcomeTrigger(
                      CompensationTriggerKind.FINAL_FAILURE,
                      "step",
                      FailureClassification.FINAL,
                      "com.example.Declined",
                      1,
                      AT,
                      "com.example.CapturePayment"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("commandType must be null unless kind is COMMAND_FAILED");
      assertThat(
              new OutcomeTrigger(
                      CompensationTriggerKind.FINAL_FAILURE,
                      "step",
                      FailureClassification.FINAL,
                      "com.example.Declined",
                      1,
                      AT)
                  .commandType())
          .isNull();
    }

    @Test
    void aFailureKindNeedsItsFacts() {
      assertThatThrownBy(
              () ->
                  new OutcomeTrigger(
                      CompensationTriggerKind.FINAL_FAILURE, "step", null, null, null, AT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("classification");
      assertThatThrownBy(
              () ->
                  new OutcomeTrigger(
                      CompensationTriggerKind.FAILURE_OUTCOME,
                      "step",
                      FailureClassification.FINAL,
                      null,
                      null,
                      AT))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void outcomeEventsAreNotDomainEvents() {
    assertThat(
            SagaCompleted.class.isAnnotationPresent(
                io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent.class))
        .isFalse();
    assertThat(
            SagaFailed.class.isAnnotationPresent(
                io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent.class))
        .isFalse();
    assertThat(
            SagaCompensated.class.isAnnotationPresent(
                io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent.class))
        .isFalse();
  }
}
