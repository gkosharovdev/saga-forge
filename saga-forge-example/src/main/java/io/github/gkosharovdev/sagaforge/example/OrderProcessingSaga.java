package io.github.gkosharovdev.sagaforge.example;

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
import io.github.gkosharovdev.sagaforge.example.commands.ProcessPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.RefundPaymentCommand;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent;
import io.github.gkosharovdev.sagaforge.example.events.StockUnavailableEvent;

/**
 * Canonical Saga Forge example: an order is paid for, its stock is reserved, and the saga ends; if the stock turns
 * out to be unavailable after the payment, the payment is refunded.
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
 *       and saved through {@link OrderRepository})</td><td>{@link OrderCompletedEvent}, or
 *       {@link StockUnavailableEvent} when the stock is not available</td></tr>
 *   <tr><td>{@code completeOrder} ({@code @EndSaga})</td><td>{@link OrderCompletedEvent}</td>
 *       <td>nothing</td><td>-</td><td>-</td></tr>
 *   <tr><td>{@code rejectOrder} ({@code @StartCompensation})</td><td>{@link StockUnavailableEvent}</td>
 *       <td>nothing; compensation follows</td><td>-</td><td>-</td></tr>
 *   <tr><td>{@code compensatePayment} ({@code @SagaCompensation(forStep = "processPayment")})</td><td>-</td>
 *       <td>{@link RefundPaymentCommand}</td><td>{@link PaymentService#handle} (service)</td><td>-</td></tr>
 * </table>
 *
 * <p>Every step associates its event with the saga instance through {@code orderId}. The
 * {@link OrderCreatedEvent} is typically published from application code through the {@code EventPublisher},
 * which opens the saga-scoped unit of work. The events recorded by the two handlers trigger steps of the
 * same saga instance, so they are delivered within that unit of work, and the unit of work commits at the
 * {@code @EndSaga} step {@code completeOrder}: the payment and the order change either both commit or both
 * roll back. A failure anywhere in the chain rolls the whole unit of work back and the saga is
 * {@code FAILED}; values already written to the {@link SagaContext} stay readable. A transient failure, such as a
 * payment provider timeout, is retried first under the failing step's {@code @RetryPolicy}: the whole unit of work
 * runs again, and every command keeps its identity, so the provider sees the same idempotency key.</p>
 *
 * <p>A business failure is declared, not thrown: when the order cannot be supplied, {@link Order} records
 * {@link StockUnavailableEvent} and the {@code @StartCompensation} step {@code rejectOrder} handles it. Its unit of
 * work commits with the payment step that preceded it, and the instance becomes {@code COMPENSATING}. The engine then
 * runs the compensation of every other completed step, most recently completed first, each in its own unit of work:
 * here {@code compensatePayment} refunds the payment, using the payment reference and amount kept in the
 * {@link SagaContext}, and the saga ends {@code COMPENSATED} with {@code rejectOrder} recorded as its trigger. The
 * refund command carries its own stable identity, so a refund that runs again after a crash refunds once.</p>
 */
@Saga
public class OrderProcessingSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    /**
     * Starts the saga for a new order, remembers its amount, and asks for payment. The payment provider can time out
     * ({@link PaymentGatewayTimeoutException}, a transient failure), so the step's unit of work may run up to three
     * times, 200 ms then 400 ms apart; {@link PaymentService} passes the command's identity to the provider, so a
     * retry never charges twice.
     */
    @StartSaga
    @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
            multiplier = 2.0, maxBackoffMillis = 1_000))
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
     * Failure outcome "rejectOrder": the order's stock is not available after it was paid for. Runs like any step;
     * when its unit of work commits, the saga compensates the steps that completed before it.
     */
    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailableEvent event) {
        // The trigger records why; the compensation reads what it needs from the SagaContext.
    }

    /**
     * Undoes {@code processPayment}: refunds the payment it recorded. Runs in its own unit of work after the saga
     * entered compensation; {@code trigger} says why (here always the stock shortage).
     */
    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment(CompensationTrigger trigger) {
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        BigDecimal amount = context.getState("orderAmount", BigDecimal.class).orElseThrow();
        commandGateway.send(new RefundPaymentCommand(sagaId, paymentId, amount));
    }
}
