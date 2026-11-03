package io.github.gkosharovdev.sagaforge.example;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;

/**
 * Canonical Saga Forge example: an order is paid for, its stock is reserved, and the saga ends.
 *
 * <p>The model is Axon-style. Every step is triggered by a domain event and returns {@code void}; a step
 * dispatches commands through the {@link CommandGateway} field, a {@code @CommandHandler} on an aggregate or a
 * service handles each command, and the handler records the next domain event with
 * {@code DomainEvents.record}. Values a later step needs are kept in the {@link SagaContext} field. Both
 * fields are recognized by their type and populated by the engine.</p>
 *
 * <table>
 *   <caption>Step, triggering event, dispatched command, and handler</caption>
 *   <tr><th>Step</th><th>Triggered by</th><th>Dispatches</th><th>Handled by</th><th>Records</th></tr>
 *   <tr><td>{@code handleOrderCreated} ({@code @StartSaga})</td><td>{@link OrderCreatedEvent}</td>
 *       <td>{@link ProcessPaymentCommand}</td><td>{@link PaymentService#handle} (service)</td>
 *       <td>{@link PaymentProcessedEvent}</td></tr>
 *   <tr><td>{@code processPayment}</td><td>{@link PaymentProcessedEvent}</td>
 *       <td>{@link ReserveStockCommand}</td><td>{@link Order#handle} (aggregate instance method, loaded
 *       and saved through {@link OrderRepository})</td><td>{@link OrderCompletedEvent}</td></tr>
 *   <tr><td>{@code completeOrder} ({@code @EndSaga})</td><td>{@link OrderCompletedEvent}</td>
 *       <td>nothing</td><td>-</td><td>-</td></tr>
 * </table>
 *
 * <p>Every step associates its event with the saga instance through {@code orderId}. The
 * {@link OrderCreatedEvent} is typically published from application code through the {@code EventPublisher},
 * which opens the saga-scoped unit of work. The events recorded by the two handlers trigger steps of the
 * same saga instance, so they are delivered within that unit of work, and the unit of work commits at the
 * {@code @EndSaga} step {@code completeOrder}: the payment and the order change either both commit or both
 * roll back. A failure anywhere in the chain rolls the whole unit of work back and the saga is
 * {@code FAILED}; values already written to the {@link SagaContext} stay readable.</p>
 *
 * <p>{@code compensatePayment} keeps the existing step-targeted compensation mapping. When and how
 * compensation runs is specified separately and is not part of the unit-of-work model shown here.</p>
 */
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    /**
     * Starts the saga for a new order, remembers its amount, and asks for payment.
     */
    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleOrderCreated(OrderCreatedEvent event) {
        context.putState("orderAmount", event.amount());
        commandGateway.send(new ProcessPaymentCommand(event.orderId(), event.amount()));
    }

    /**
     * Checks the paid amount against the one remembered by {@code handleOrderCreated}, records the payment
     * reference, and asks the order to reserve its stock. Throwing here rolls back the unit of work,
     * including the payment.
     */
    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessedEvent event) {
        BigDecimal orderAmount = context.getState("orderAmount", BigDecimal.class).orElseThrow();
        if (orderAmount.compareTo(event.amount()) != 0) {
            throw new IllegalStateException("Payment " + event.paymentId() + " of " + event.amount()
                    + " does not match order amount " + orderAmount);
        }
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new ReserveStockCommand(event.orderId()));
    }

    /**
     * Outcome "completed": the order is paid and its stock reserved. Dispatches nothing; the saga-scoped
     * unit of work commits when this step returns and the saga is {@code COMPLETED}.
     */
    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompletedEvent event) {
        // Terminal step: no further command to dispatch.
    }

    /**
     * Compensation mapped to {@code processPayment}. Its execution semantics belong to a separate spec.
     */
    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment() {
        System.out.println("Compensating payment for saga: " + sagaId);
    }
}
