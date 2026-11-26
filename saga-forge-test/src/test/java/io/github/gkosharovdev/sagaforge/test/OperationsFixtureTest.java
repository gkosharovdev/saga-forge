package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RecoveryReport;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.RecoverySettings;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.CardDeclined;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.RefundPayment;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockMissing;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Operations through the fixture ({@code specs/015-saga-operations} tasks.md T059 to T061): resume,
 * compensate and resolve, the history and resolution expectations, and recovery on time.
 */
class OperationsFixtureTest {

  private static final OperatorRequest ALICE = new OperatorRequest("alice", "refund settled");

  /** A refund that fails once: the saga ends COMPENSATION_FAILED. */
  private static SagaFixture refundFailsOnce() {
    SagaFixture fixture =
        SagaFixture.of(ShopSaga.class)
            .failCommand(RefundPayment.class, CardDeclined::new, 1)
            .build();
    fixture
        .given(new OrderPlaced("o-1", "10"), new PaymentTaken("payment-1", "o-1", "10"))
        .when(new StockMissing("o-1"))
        .expectStatus(SagaStatus.COMPENSATION_FAILED);
    return fixture;
  }

  @Test
  @DisplayName("a failed compensation is resumed by an operator and ends COMPENSATED")
  void resume() {
    SagaFixture fixture = refundFailsOnce();
    String sagaId = fixture.instance(ShopSaga.class, "o-1").sagaId();

    fixture
        .whenOperator(operations -> operations.resumeCompensation(sagaId, ALICE))
        .instance(ShopSaga.class, "o-1")
        .expectStatus(SagaStatus.COMPENSATED)
        .expectCommands(new RefundPayment("o-1", "payment-1", "10"))
        .expectHistory(
            SagaLifecycleEventKind.COMPENSATION_FAILED,
            SagaLifecycleEventKind.COMPENSATION_RESUMED,
            SagaLifecycleEventKind.COMPENSATED)
        .expectNotResolved();
    assertThat(fixture.instance(ShopSaga.class, "o-1").stored().compensationRound()).isEqualTo(1);
  }

  @Test
  @DisplayName("resolve marks the instance; expectResolved and expectNotResolved tell them apart")
  void resolve() {
    SagaFixture fixture = refundFailsOnce();
    String sagaId = fixture.instance(ShopSaga.class, "o-1").sagaId();

    RepairResult result = fixture.operations().markResolved(sagaId, ALICE);

    assertThat(result).isInstanceOf(RepairResult.Applied.class);
    assertThat(fixture.instance(ShopSaga.class, "o-1").resolution())
        .hasValueSatisfying(resolution -> assertThat(resolution.operator()).isEqualTo("alice"));
    fixture
        .whenOperator(operations -> {})
        .instance(ShopSaga.class, "o-1")
        .expectResolved()
        .expectHistory(SagaLifecycleEventKind.RESOLVED);
    assertThatThrownBy(
            () ->
                fixture
                    .whenOperator(operations -> {})
                    .instance(ShopSaga.class, "o-1")
                    .expectNotResolved())
        .isInstanceOf(SagaExpectationError.class);
    assertThatThrownBy(
            () ->
                fixture
                    .whenOperator(operations -> {})
                    .instance(ShopSaga.class, "o-1")
                    .expectHistory(SagaLifecycleEventKind.RESOLVED, SagaLifecycleEventKind.CREATED))
        .isInstanceOf(SagaExpectationError.class)
        .hasMessageContaining("expected history");
  }

  @Test
  @DisplayName("an operator compensates a running saga; its refund command is delivered")
  void compensate() {
    SagaFixture fixture = SagaFixture.of(ShopSaga.class).build();
    fixture.given(new OrderPlaced("o-2", "10"), new PaymentTaken("payment-2", "o-2", "10"));
    String sagaId = fixture.instance(ShopSaga.class, "o-2").sagaId();

    fixture
        .whenOperator(operations -> operations.compensate(sagaId, ALICE))
        .instance(ShopSaga.class, "o-2")
        .expectStatus(SagaStatus.COMPENSATED)
        .expectCompensations(Shop.REFUND)
        .expectCommands(new RefundPayment("o-2", "payment-2", "10"));
  }

  @Test
  @DisplayName("with recovery enabled, time advancing runs a sweep; nothing stalled, nothing done")
  void recoveryOnTime() {
    SagaFixture fixture =
        SagaFixture.of(ShopSaga.class).recovery(RecoverySettings.enabledWithDefaults()).build();
    fixture.given(new OrderPlaced("o-3", "10")).givenTimeElapses(Duration.ofMinutes(20));

    assertThat(fixture.operations().recover()).isEqualTo(RecoveryReport.none());
    assertThat(fixture.instance(ShopSaga.class, "o-3").status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(Thread.getAllStackTraces().keySet())
        .noneMatch(thread -> thread.getName().equals("saga-forge-recovery"));
  }
}
