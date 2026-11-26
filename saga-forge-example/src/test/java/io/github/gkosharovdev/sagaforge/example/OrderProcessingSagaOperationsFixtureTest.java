package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.example.commands.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;
import io.github.gkosharovdev.sagaforge.example.events.StockUnavailableEvent;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Operating the canonical saga ({@code specs/015-saga-operations} quickstart §2): a refund the
 * payment provider refuses once leaves the saga {@code COMPENSATION_FAILED}; an operator resumes
 * the compensation once the provider is back, and the saga ends {@code COMPENSATED}.
 */
class OrderProcessingSagaOperationsFixtureTest {

  private static final BigDecimal AMOUNT = new BigDecimal("10.00");

  /** The provider refuses the refund for good, this time. */
  static final class RefundRefused extends RuntimeException implements FinalFailure {
    RefundRefused() {
      super("refund refused by the provider");
    }
  }

  @Test
  @DisplayName("a refund refused once: the operator resumes the compensation and it completes")
  void resumeAfterRefusedRefund() {
    SagaFixture fixture =
        SagaFixture.of(OrderProcessingSaga.class)
            .failCommand(RefundPaymentCommand.class, RefundRefused::new, 1)
            .build();
    fixture
        .given(
            new OrderCreatedEvent("o-1", AMOUNT),
            new PaymentProcessedEvent("payment-1", "o-1", AMOUNT))
        .when(new StockUnavailableEvent("o-1"))
        .expectStatus(SagaStatus.COMPENSATION_FAILED);
    String sagaId = fixture.instance(OrderProcessingSaga.class, "o-1").sagaId();

    fixture
        .whenOperator(
            operations ->
                operations.resumeCompensation(
                    sagaId, new OperatorRequest("alice", "provider back, refund retried")))
        .instance(OrderProcessingSaga.class, "o-1")
        .expectStatus(SagaStatus.COMPENSATED)
        .expectCommands(new RefundPaymentCommand(sagaId, "payment-1", AMOUNT))
        .expectHistory(
            SagaLifecycleEventKind.COMPENSATION_FAILED,
            SagaLifecycleEventKind.COMPENSATION_RESUMED,
            SagaLifecycleEventKind.COMPENSATED)
        .expectOutcomeEvent(
            SagaCompensated.class,
            compensated -> compensated.eventId().equals(sagaId + ":COMPENSATED:r1"));
  }
}
