package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.SagaResolution;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepCompensation;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The forward-transition rules of {@code specs/015-saga-operations} (research.md R12,
 * contracts/storage-query-spi.md F1 to F8).
 */
class ForwardTransitionOperationsTest {

  private static final Instant AT = Instant.parse("2026-11-26T10:00:00Z");
  private static final OperatorRequest REQUEST = new OperatorRequest("alice", "retry the refund");
  private static final SagaFailure FAILURE =
      new SagaFailure("pay", "java.lang.IllegalStateException", "declined", AT);
  private static final CompensationTrigger FAILURE_OUTCOME =
      CompensationTrigger.failureOutcome("reject", "StockUnavailableEvent", AT);

  private static SagaInstance instance(
      SagaStatus status,
      SagaFailure failure,
      CompensationTrigger trigger,
      StepCompensation payCompensation,
      SagaResolution resolution,
      int round) {
    StepRecord pay = StepRecord.completed(List.of()).withCompensation(payCompensation);
    return new SagaInstance(
        "saga-1",
        "OrderSaga",
        Map.of(),
        Map.of("orderId", "o-1"),
        status,
        AT,
        AT,
        Map.of("pay", pay),
        status == SagaStatus.COMPLETED ? "done" : null,
        failure,
        null,
        trigger,
        Map.of(),
        resolution,
        round);
  }

  private static SagaInstance compensationFailed(int round) {
    return instance(
        SagaStatus.COMPENSATION_FAILED,
        null,
        FAILURE_OUTCOME,
        StepCompensation.failed(FAILURE),
        null,
        round);
  }

  private static SagaInstance resumed(int round) {
    return instance(
        SagaStatus.COMPENSATING,
        null,
        FAILURE_OUTCOME,
        StepCompensation.notAttempted(),
        null,
        round);
  }

  private static SagaInstance failed(SagaResolution resolution) {
    return instance(SagaStatus.FAILED, FAILURE, null, null, resolution, 0);
  }

  private static SagaInstance compensatingBy(CompensationTrigger trigger) {
    return instance(
        SagaStatus.COMPENSATING, null, trigger, StepCompensation.notAttempted(), null, 0);
  }

  @Test
  @DisplayName("F1: COMPENSATION_FAILED -> COMPENSATING with round + 1 and FAILED -> NOT_ATTEMPTED")
  void resumeWithTheNextRound() {
    assertThatCode(
            () -> ForwardTransition.requireForwardTransition(compensationFailed(0), resumed(1)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("F2: the same resume without the round increment is rejected")
  void resumeWithoutTheRound() {
    assertThatThrownBy(
            () -> ForwardTransition.requireForwardTransition(compensationFailed(0), resumed(0)))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
    assertThatThrownBy(
            () -> ForwardTransition.requireForwardTransition(compensationFailed(0), resumed(2)))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
  }

  @Test
  @DisplayName("F3: FAILED -> COMPENSATING with a new OPERATOR_REQUEST trigger")
  void compensateByOperator() {
    CompensationTrigger byOperator = CompensationTrigger.byOperator(REQUEST, "pay", FAILURE, AT);

    assertThatCode(
            () ->
                ForwardTransition.requireForwardTransition(
                    failed(null), compensatingBy(byOperator)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("F4: FAILED -> COMPENSATING with any other trigger is rejected")
  void compensateWithAnotherTrigger() {
    assertThatThrownBy(
            () ->
                ForwardTransition.requireForwardTransition(
                    failed(null), compensatingBy(FAILURE_OUTCOME)))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
  }

  @Test
  @DisplayName("F5: an unresolved FAILED instance may get a resolution")
  void resolve() {
    assertThatCode(
            () ->
                ForwardTransition.requireForwardTransition(
                    failed(null), failed(SagaResolution.of(REQUEST, AT))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("F6: a recorded resolution never changes or disappears")
  void resolutionIsFinal() {
    SagaInstance resolved = failed(SagaResolution.of(REQUEST, AT));

    assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(resolved, failed(null)))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
    assertThatThrownBy(
            () ->
                ForwardTransition.requireForwardTransition(
                    resolved, failed(SagaResolution.of(new OperatorRequest("bob", "other"), AT))))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
  }

  @Test
  @DisplayName("F7: the compensation round never decreases")
  void roundNeverDecreases() {
    assertThatThrownBy(
            () ->
                ForwardTransition.requireForwardTransition(
                    compensationFailed(2), compensationFailed(1)))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
  }

  @Test
  @DisplayName("F8: COMPLETED stays COMPLETED")
  void completedIsFinal() {
    SagaInstance completed = instance(SagaStatus.COMPLETED, null, null, null, null, 0);

    assertThatThrownBy(
            () ->
                ForwardTransition.requireForwardTransition(
                    completed,
                    compensatingBy(CompensationTrigger.byOperator(REQUEST, "pay", null, AT))))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
    assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(completed, resumed(1)))
        .isInstanceOf(ConcurrentSagaUpdateException.class);
  }
}
