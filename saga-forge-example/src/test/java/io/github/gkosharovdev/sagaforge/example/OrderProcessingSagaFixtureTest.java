package io.github.gkosharovdev.sagaforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;
import io.github.gkosharovdev.sagaforge.example.events.StockUnavailableEvent;
import io.github.gkosharovdev.sagaforge.test.RecordedCommand;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;
import io.github.gkosharovdev.sagaforge.test.Then;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The reference fixture tests of the canonical saga ({@code specs/009-saga-test-fixture} FR-018,
 * SC-001, SC-002): its happy path and its compensation path in record-and-answer mode, and a
 * retried payment with the real {@link PaymentService}. No database, transport or Spring context;
 * they run in well under a second together. Since {@code specs/011-saga-outcome-events} they also
 * assert the outcome event each ending announces.
 */
@Timeout(value = 1, unit = TimeUnit.SECONDS)
class OrderProcessingSagaFixtureTest {

  private static final BigDecimal AMOUNT = new BigDecimal("10.00");

  @Test
  @DisplayName("happy path: the payment reserves the stock")
  void paymentReservesStock() {
    SagaFixture.of(OrderProcessingSaga.class)
        .build()
        .given(new OrderCreatedEvent("o-1", AMOUNT))
        .when(new PaymentProcessedEvent("payment-1", "o-1", AMOUNT))
        .expectCommands(new ReserveStockCommand("o-1"))
        .expectContext("paymentId", "payment-1")
        .expectCompletedStep("processPayment")
        .expectStatus(SagaStatus.RUNNING)
        .expectNoOutcomeEvent();
  }

  @Test
  @DisplayName("completion: the order's completion ends the saga and announces SagaCompleted")
  void completionIsAnnounced() {
    SagaFixture.of(OrderProcessingSaga.class)
        .build()
        .given(
            new OrderCreatedEvent("o-4", AMOUNT),
            new PaymentProcessedEvent("payment-4", "o-4", AMOUNT))
        .when(new OrderCompletedEvent("o-4"))
        .expectEndedWith("completeOrder")
        .expectOutcomeEvent(
            SagaCompleted.class,
            completed ->
                completed.outcomeStep().equals("completeOrder")
                    && completed.associations().get("orderId").equals("o-4"));
  }

  @Test
  @DisplayName("compensation path: missing stock refunds the payment")
  void missingStockRefundsThePayment() {
    SagaFixture.of(OrderProcessingSaga.class)
        .build()
        .given(
            new OrderCreatedEvent("o-2", AMOUNT),
            new PaymentProcessedEvent("payment-2", "o-2", AMOUNT))
        .when(new StockUnavailableEvent("o-2"))
        .expectCompensations("compensatePayment")
        .expectCommands(new RefundPaymentCommand("saga-1", "payment-2", AMOUNT))
        .expectCompensatedAfter("rejectOrder")
        .expectOutcomeEvent(
            SagaCompensated.class,
            compensated ->
                compensated.trigger().kind() == CompensationTriggerKind.FAILURE_OUTCOME
                    && compensated.trigger().stepName().equals("rejectOrder"));
  }

  @Test
  @DisplayName(
      "start-step failure: the payment keeps timing out, the saga fails and announces SagaFailed")
  void startFailureIsAnnounced() {
    PaymentProvider provider = new PaymentProvider();
    provider.timeOutNext(10);
    SagaFixture fixture =
        SagaFixture.of(OrderProcessingSaga.class).handlers(new PaymentService(provider)).build();

    fixture
        .when(new OrderCreatedEvent("o-5", AMOUNT))
        .expectStatus(SagaStatus.FAILED)
        .expectOutcomeEvent(
            SagaFailed.class,
            failed ->
                failed.failedStep().equals("handleOrderCreated")
                    && failed.classification() == FailureClassification.EXHAUSTED
                    && failed.attempts() == 3);
  }

  @Test
  @DisplayName("retried payment: two provider timeouts, one charge, the same idempotency key")
  void retriedPayment() {
    PaymentProvider provider = new PaymentProvider();
    provider.timeOutNext(2);
    SagaFixture fixture =
        SagaFixture.of(OrderProcessingSaga.class).handlers(new PaymentService(provider)).build();

    Then then =
        fixture
            .when(new OrderCreatedEvent("o-3", AMOUNT))
            .expectAttempts("handleOrderCreated", 3)
            .expectCommands(
                new ProcessPaymentCommand("o-3", AMOUNT), new ReserveStockCommand("o-3"));

    assertThat(then.result().allAttempts())
        .filteredOn(c -> c.command() instanceof ProcessPaymentCommand)
        .extracting(RecordedCommand::commandId)
        .hasSize(3)
        .containsOnly(then.result().commands().get(0).commandId());
    assertThat(provider.charges()).hasSize(1);
  }
}
