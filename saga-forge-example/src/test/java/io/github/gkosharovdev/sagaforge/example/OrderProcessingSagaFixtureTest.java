package io.github.gkosharovdev.sagaforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;
import io.github.gkosharovdev.sagaforge.example.events.StockUnavailableEvent;
import io.github.gkosharovdev.sagaforge.test.RecordedCommand;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;
import io.github.gkosharovdev.sagaforge.test.Then;

/**
 * The reference fixture tests of the canonical saga ({@code specs/009-saga-test-fixture} FR-018, SC-001, SC-002): its
 * happy path and its compensation path in record-and-answer mode, and a retried payment with the real
 * {@link PaymentService}. No database, transport or Spring context; the three run in well under a second together.
 */
@Timeout(value = 1, unit = TimeUnit.SECONDS)
class OrderProcessingSagaFixtureTest {

    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Test
    @DisplayName("happy path: the payment reserves the stock")
    void paymentReservesStock() {
        SagaFixture.of(OrderProcessingSaga.class).build()
                .given(new OrderCreatedEvent("o-1", AMOUNT))
                .when(new PaymentProcessedEvent("payment-1", "o-1", AMOUNT))
                .expectCommands(new ReserveStockCommand("o-1"))
                .expectContext("paymentId", "payment-1")
                .expectCompletedStep("processPayment")
                .expectStatus(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("compensation path: missing stock refunds the payment")
    void missingStockRefundsThePayment() {
        SagaFixture.of(OrderProcessingSaga.class).build()
                .given(new OrderCreatedEvent("o-2", AMOUNT), new PaymentProcessedEvent("payment-2", "o-2", AMOUNT))
                .when(new StockUnavailableEvent("o-2"))
                .expectCompensations("compensatePayment")
                .expectCommands(new RefundPaymentCommand("saga-1", "payment-2", AMOUNT))
                .expectCompensatedAfter("rejectOrder");
    }

    @Test
    @DisplayName("retried payment: two provider timeouts, one charge, the same idempotency key")
    void retriedPayment() {
        PaymentProvider provider = new PaymentProvider();
        provider.timeOutNext(2);
        SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class).handlers(new PaymentService(provider)).build();

        Then then = fixture.when(new OrderCreatedEvent("o-3", AMOUNT))
                .expectAttempts("handleOrderCreated", 3)
                .expectCommands(new ProcessPaymentCommand("o-3", AMOUNT), new ReserveStockCommand("o-3"));

        assertThat(then.result().allAttempts()).filteredOn(c -> c.command() instanceof ProcessPaymentCommand)
                .extracting(RecordedCommand::commandId).hasSize(3).containsOnly(then.result().commands().get(0)
                        .commandId());
        assertThat(provider.charges()).hasSize(1);
    }
}
