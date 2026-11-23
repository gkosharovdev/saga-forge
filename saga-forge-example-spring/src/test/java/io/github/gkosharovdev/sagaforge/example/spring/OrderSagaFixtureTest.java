package io.github.gkosharovdev.sagaforge.example.spring;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.example.spring.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.spring.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.spring.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;

/**
 * The Spring application's saga tested with the fixture and the application's own handler bean, built by hand with
 * its collaborator: no Spring context, database or transport ({@code specs/009-saga-test-fixture} US5, SC-006).
 */
class OrderSagaFixtureTest {

    private final PaymentProvider provider = new PaymentProvider();
    private final SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class, Order.class)
            .handlers(new PaymentService(provider))
            .build();

    @Test
    @DisplayName("an in-stock order is paid for and completed in one call")
    void completes() {
        BigDecimal amount = new BigDecimal("25.00");

        fixture.givenAggregates(Order.create("o-1", true))
                .when(new OrderCreatedEvent("o-1", amount))
                .expectCommands(new ProcessPaymentCommand("o-1", amount), new ReserveStockCommand("o-1"))
                .expectContext("orderAmount", "25.00")
                .expectEndedWith("completeOrder")
                .expectOutcomeEvent(SagaCompleted.class, completed -> completed.outcomeStep().equals("completeOrder"));

        assertThat(fixture.aggregate(Order.class, "o-1").orElseThrow().status()).isEqualTo(Order.Status.COMPLETED);
        assertThat(provider.charges()).containsValue(amount);
    }

    @Test
    @DisplayName("an out-of-stock order is refunded")
    void refunds() {
        fixture.givenAggregates(Order.create("o-2", false))
                .when(new OrderCreatedEvent("o-2", new BigDecimal("9.50")))
                .expectCompensations("compensatePayment")
                .expectCompensatedAfter("rejectOrder")
                .expectStatus(SagaStatus.COMPENSATED)
                .expectOutcomeEvent(SagaCompensated.class,
                        compensated -> compensated.trigger().stepName().equals("rejectOrder"));

        assertThat(provider.refunds()).containsValue(new BigDecimal("9.50"));
    }

    @Test
    @DisplayName("a declined payment fails the saga and announces SagaFailed")
    void declined() {
        fixture.givenAggregates(Order.create("o-3", true))
                .when(new OrderCreatedEvent("o-3", new BigDecimal("-1.00")))
                .expectStatus(SagaStatus.FAILED)
                .expectOutcomeEvent(SagaFailed.class, failed -> failed.failedStep().equals("handleOrderCreated")
                        && failed.causeType().equals(IllegalArgumentException.class.getName()));
    }
}
