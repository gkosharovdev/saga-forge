package io.github.gkosharovdev.sagaforge.example.spring;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.example.spring.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.spring.commands.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.spring.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.spring.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.spring.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.spring.events.PaymentProcessedEvent;
import io.github.gkosharovdev.sagaforge.example.spring.events.StockUnavailableEvent;

/**
 * The canonical saga ({@code saga-forge-example}'s {@code OrderProcessingSaga}): pay, reserve stock, complete; or, when
 * the stock is unavailable after payment, refund. Amounts are kept in the {@link SagaContext} as plain strings, since
 * Postgres stores context values as JSON scalars (memory.md G-003-07).
 */
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
            multiplier = 2.0, maxBackoffMillis = 1_000))
    public void handleOrderCreated(OrderCreatedEvent event) {
        context.putState("orderAmount", event.amount().toPlainString());
        commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        BigDecimal orderAmount = amount();
        if (orderAmount.compareTo(event.amount()) != 0) {
            throw new IllegalStateException("Payment " + event.paymentId() + " of " + event.amount()
                    + " does not match order amount " + orderAmount);
        }
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new ReserveStockCommand(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
        // Terminal step: the unit of work commits and the saga is COMPLETED.
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailableEvent event) {
        // The trigger records why; compensatePayment follows.
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId, amount()));
    }

    private BigDecimal amount() {
        return new BigDecimal(context.getState("orderAmount", String.class).orElseThrow());
    }
}
