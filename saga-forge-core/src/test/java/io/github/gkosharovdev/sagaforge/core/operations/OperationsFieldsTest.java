package io.github.gkosharovdev.sagaforge.core.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.OutcomeTrigger;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.telemetry.SagaTelemetrySettings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The fields {@code specs/015-saga-operations} adds to existing core types. */
class OperationsFieldsTest {

  private static final Instant AT = Instant.parse("2026-11-26T10:00:00Z");
  private static final OperatorRequest REQUEST = new OperatorRequest("alice", "refunded by hand");
  private static final SagaFailure FAILURE =
      new SagaFailure("processPayment", "java.lang.IllegalStateException", "declined", AT);

  private static SagaInstance failed() {
    return new SagaInstance(
        "saga-1",
        "OrderSaga",
        Map.of(),
        Map.of(),
        SagaStatus.FAILED,
        AT,
        AT,
        Map.of(),
        null,
        FAILURE);
  }

  @Nested
  @DisplayName("SagaInstance resolution and compensation round")
  class Instances {

    @Test
    void oldConstructorsDefaultToNoResolutionAndRoundZero() {
      var instance = failed();

      assertThat(instance.resolution()).isNull();
      assertThat(instance.compensationRound()).isZero();
      assertThat(instance.isResolved()).isFalse();
    }

    @Test
    void resolutionIsAllowedOnFailedAndCompensationFailedOnly() {
      var resolved = failed().withResolution(SagaResolution.of(REQUEST, AT));

      assertThat(resolved.isResolved()).isTrue();
      assertThat(resolved.status()).isEqualTo(SagaStatus.FAILED);
      assertThatThrownBy(
              () ->
                  new SagaInstance(
                          "saga-1",
                          "OrderSaga",
                          Map.of(),
                          Map.of(),
                          SagaStatus.RUNNING,
                          AT,
                          AT,
                          Map.of(),
                          null,
                          null)
                      .withResolution(SagaResolution.of(REQUEST, AT)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withersCarryTheNewFields() {
      var instance =
          failed().withResolution(SagaResolution.of(REQUEST, AT)).withCompensationRound(2);

      var touched = instance.withPendingRetry(null).withOpenDeadlines(Map.of());

      assertThat(touched.resolution()).isEqualTo(instance.resolution());
      assertThat(touched.compensationRound()).isEqualTo(2);
    }

    @Test
    void roundMustNotBeNegative() {
      assertThatThrownBy(() -> failed().withCompensationRound(-1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("SagaLifecycleEntry operator")
  class Entries {

    @Test
    void resumedAndResolvedRequireAnOperator() {
      for (SagaLifecycleEventKind kind :
          List.of(SagaLifecycleEventKind.COMPENSATION_RESUMED, SagaLifecycleEventKind.RESOLVED)) {
        assertThatThrownBy(
                () ->
                    new SagaLifecycleEntry(
                        "saga-1", kind, null, List.of(), null, null, AT, null, null))
            .as(kind.name())
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                new SagaLifecycleEntry(
                        "saga-1", kind, null, List.of(), null, null, AT, null, REQUEST)
                    .operator())
            .isEqualTo(REQUEST);
      }
    }

    @Test
    void startRecoveredNeedsNoOperatorAndOldConstructorsLeaveItNull() {
      var recovered =
          new SagaLifecycleEntry(
              "saga-1",
              SagaLifecycleEventKind.START_RECOVERED,
              null,
              List.of(),
              null,
              null,
              AT,
              null,
              null);
      var completed =
          new SagaLifecycleEntry(
              "saga-1", SagaLifecycleEventKind.COMPLETED, null, List.of(), "done", null, AT);

      assertThat(recovered.operator()).isNull();
      assertThat(completed.operator()).isNull();
    }
  }

  @Nested
  @DisplayName("OPERATOR_REQUEST triggers")
  class Triggers {

    @Test
    void byOperatorWithAndWithoutAFailure() {
      var withFailure = CompensationTrigger.byOperator(REQUEST, "processPayment", FAILURE, AT);
      var withoutFailure = CompensationTrigger.byOperator(REQUEST, "processPayment", null, AT);

      assertThat(withFailure.kind()).isEqualTo(CompensationTriggerKind.OPERATOR_REQUEST);
      assertThat(withFailure.failure()).isEqualTo(FAILURE);
      assertThat(withFailure.operatorRequest()).isEqualTo(REQUEST);
      assertThat(withoutFailure.failure()).isNull();
      assertThat(withFailure.eventType()).isNull();
      assertThat(withFailure.commandType()).isNull();
      assertThat(withFailure.deadlineName()).isNull();
    }

    @Test
    void operatorRequestIsPresentIffTheKindIsOperatorRequest() {
      assertThatThrownBy(
              () ->
                  new CompensationTrigger(
                      CompensationTriggerKind.OPERATOR_REQUEST,
                      "processPayment",
                      null,
                      null,
                      null,
                      AT,
                      null,
                      null))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  new CompensationTrigger(
                      CompensationTriggerKind.FAILURE_OUTCOME,
                      "rejectOrder",
                      "StockUnavailableEvent",
                      null,
                      null,
                      AT,
                      null,
                      REQUEST))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> CompensationTrigger.byOperator(null, "processPayment", null, AT))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outcomeTriggerMapsTheOperatorRequest() {
      var withFailure =
          OutcomeTrigger.of(CompensationTrigger.byOperator(REQUEST, "processPayment", FAILURE, AT));
      var withoutFailure =
          OutcomeTrigger.of(CompensationTrigger.byOperator(REQUEST, "processPayment", null, AT));

      assertThat(withFailure.operatorRequest()).isEqualTo(REQUEST);
      assertThat(withFailure.classification()).isEqualTo(FailureClassification.UNCLASSIFIED);
      assertThat(withFailure.causeType()).isEqualTo("java.lang.IllegalStateException");
      assertThat(withoutFailure.operatorRequest()).isEqualTo(REQUEST);
      assertThat(withoutFailure.classification()).isNull();
      assertThat(withoutFailure.attempts()).isNull();
    }

    @Test
    void outcomeTriggerRejectsAPartialFailureForOperatorRequests() {
      assertThatThrownBy(
              () ->
                  new OutcomeTrigger(
                      CompensationTriggerKind.OPERATOR_REQUEST,
                      "processPayment",
                      FailureClassification.FINAL,
                      null,
                      null,
                      AT,
                      null,
                      null,
                      REQUEST))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("outcome event ids per compensation round")
  class OutcomeIds {

    private final OutcomeTrigger trigger =
        OutcomeTrigger.of(CompensationTrigger.byOperator(REQUEST, "processPayment", null, AT));

    @Test
    void roundZeroKeepsTheOldId() {
      assertThat(
              SagaCompensated.of("OrderSaga", "saga-1", Map.of("orderId", "o-1"), AT, trigger, 0)
                  .eventId())
          .isEqualTo("saga-1:COMPENSATED");
    }

    @Test
    void laterRoundsAppendTheRound() {
      assertThat(
              SagaCompensated.of("OrderSaga", "saga-1", Map.of("orderId", "o-1"), AT, trigger, 2)
                  .eventId())
          .isEqualTo("saga-1:COMPENSATED:r2");
      assertThat(
              SagaFailed.inCompensation(
                      "OrderSaga",
                      "saga-1",
                      Map.of("orderId", "o-1"),
                      AT,
                      "processPayment",
                      FailureClassification.UNCLASSIFIED,
                      "java.lang.IllegalStateException",
                      1,
                      trigger,
                      1)
                  .eventId())
          .isEqualTo("saga-1:COMPENSATION_FAILED:r1");
    }
  }

  @Nested
  @DisplayName("SagaTelemetrySettings instance count interval")
  class Telemetry {

    @Test
    void defaultsToThirtySecondsAndOldConstructorKeepsIt() {
      assertThat(SagaTelemetrySettings.defaults().instanceCountInterval())
          .isEqualTo(Duration.ofSeconds(30));
      assertThat(new SagaTelemetrySettings(true, true, false, false, false).instanceCountInterval())
          .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void withersKeepTheIntervalAndRejectNonPositiveValues() {
      var settings =
          SagaTelemetrySettings.defaults().withInstanceCountInterval(Duration.ofSeconds(5));

      assertThat(settings.withTracing(false).instanceCountInterval())
          .isEqualTo(Duration.ofSeconds(5));
      assertThatThrownBy(() -> settings.withInstanceCountInterval(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> settings.withInstanceCountInterval(null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
