package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;

/**
 * Example saga demonstrating the Saga Forge annotation model.
 *
 * <p>Flow: OrderCreated → ProcessPayment → PaymentProcessed → ReserveStock → OrderCompleted</p>
 *
 * <p>Compensation: if payment processing needs to be reversed, {@code compensatePayment()} handles rollback.</p>
 */
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public ProcessPaymentCommand handleOrderCreated(OrderCreatedEvent event) {
        System.out.println("Saga started for order: " + event.orderId());
        return new ProcessPaymentCommand(event.orderId());
    }

    @SagaStep(associationProperty = "orderId")
    public ReserveStockCommand processPayment(PaymentProcessedEvent event) {
        System.out.println("Payment processed for order: " + event.orderId());
        return new ReserveStockCommand(event.orderId());
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
        System.out.println("Order completed: " + event.orderId());
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment() {
        System.out.println("Compensating payment for saga: " + sagaId);
    }
}
