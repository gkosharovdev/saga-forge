package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.PaymentProcessedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.StockUnavailableEvent;

/**
 * The canonical order saga (saga-forge-example's {@code OrderProcessingSaga}), not a bean: built by the runtime. The
 * amount is kept as a String in the {@code SagaContext} (memory.md G-003-07).
 */
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleOrderCreated(OrderCreatedEvent event) {
        // A String: Postgres SagaContext values are JSON scalars, BigDecimal is rejected (memory.md G-003-07).
        context.putState("orderAmount", event.amount().toPlainString());
        commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new ReserveStockCommand(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
        // terminal step
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailableEvent event) {
        // compensation follows
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        BigDecimal amount = new BigDecimal(context.getState("orderAmount", String.class).orElseThrow());
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId, amount));
    }
}
