package io.github.gkosharovdev.sagaforge.example.checkout;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.AuthorizePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ConfirmOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.RejectOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleaseStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderPlacedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.PaymentAuthorizedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.PaymentDeclinedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.StockReservedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.StockUnavailableEvent;

/**
 * Checkout: from a placed order to a confirmed one, in minutes, while the customer waits. The saga holds the order's
 * stock, authorises (holds, does not take) the payment, and confirms the order. It ends there: delivery is
 * {@link FulfilmentSaga}'s job, started by the {@code OrderConfirmedEvent} this saga's last command records.
 *
 * <table>
 *   <caption>Steps of the checkout</caption>
 *   <tr><th>Step</th><th>On</th><th>Sends</th><th>Undone by</th></tr>
 *   <tr><td>{@code onOrderPlaced} ({@code @StartSaga})</td><td>{@link OrderPlacedEvent}</td>
 *       <td>{@link ReserveStockCommand} (outbox, to Inventory)</td><td>{@code cancelCheckout}</td></tr>
 *   <tr><td>{@code onStockReserved}; starts deadline {@code paymentWindow}</td><td>{@link StockReservedEvent}</td>
 *       <td>{@link AuthorizePaymentCommand} (in the unit of work, retried on a timeout)</td><td>-</td></tr>
 *   <tr><td>{@code onPaymentAuthorized} ({@code @EndSaga})</td><td>{@link PaymentAuthorizedEvent}</td>
 *       <td>{@link ConfirmOrderCommand}</td><td>-</td></tr>
 *   <tr><td>{@code onStockUnavailable} ({@code @StartCompensation})</td><td>{@link StockUnavailableEvent}</td>
 *       <td>-</td><td>-</td></tr>
 *   <tr><td>{@code onPaymentDeclined} ({@code @StartCompensation})</td><td>{@link PaymentDeclinedEvent}</td>
 *       <td>-</td><td>-</td></tr>
 *   <tr><td>{@code onPaymentWindowExpired} ({@code @StartCompensation})</td><td>deadline {@code paymentWindow}</td>
 *       <td>-</td><td>-</td></tr>
 * </table>
 *
 * <p>The payment window is the time the customer has to pass the card challenge once the stock is held: 10 minutes at
 * express checkout, 30 otherwise. It needs no {@code cancelledBy}: every step that answers the payment ends the saga or
 * starts compensation, and leaving {@code RUNNING} cancels every open deadline.</p>
 *
 * <p>{@code cancelCheckout} undoes {@code onOrderPlaced}, the step that sent {@link ReserveStockCommand}, so it runs on
 * every failure path; Inventory treats a release for an order it holds nothing for as done.</p>
 */
@Saga
public class CheckoutSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    /** Asks Inventory to hold the items. Inventory answers later, so the saga waits after this step commits. */
    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlacedEvent event) {
        context.putState("orderId", event.orderId());
        context.putState("amount", event.amount().toPlainString());
        context.putState("paymentWindow", event.express() ? "PT10M" : "PT30M");
        commandGateway.send(new ReserveStockCommand(event.orderId(), event.lines()));
    }

    /**
     * The stock is held: authorise the payment. The provider answers within the call; a provider timeout is a
     * transient failure, so this unit of work runs up to three times, 200 ms then 400 ms apart, with the same
     * idempotency key each time. The customer now has the payment window to pass the card challenge.
     */
    @SagaStep(associationProperty = "orderId",
            retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200, multiplier = 2.0))
    @StartDeadline(name = "paymentWindow", durationFrom = "paymentWindow")
    public void onStockReserved(StockReservedEvent event) {
        context.putState("reservationId", event.reservationId());
        commandGateway.send(new AuthorizePaymentCommand(event.orderId(), amount()));
    }

    /** Outcome "confirmed": the stock is held and the money authorised. */
    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPaymentAuthorized(PaymentAuthorizedEvent event) {
        String reservationId = context.getState("reservationId", String.class).orElseThrow();
        commandGateway.send(new ConfirmOrderCommand(event.orderId(), reservationId, event.paymentId()));
    }

    /** Failure outcome: an item is out of stock. */
    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onStockUnavailable(StockUnavailableEvent event) {
        context.putState("unavailableSku", event.sku());
    }

    /** Failure outcome: the card was declined. */
    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onPaymentDeclined(PaymentDeclinedEvent event) {
        context.putState("declineReason", event.reason());
    }

    /** Failure outcome: the customer abandoned the card challenge and the payment window closed. */
    @StartCompensation
    @DeadlineExpired("paymentWindow")
    @SagaStep
    public void onPaymentWindowExpired(DeadlineReached deadline) {
        context.putState("timedOutAt", deadline.dueAt().toString());
    }

    /**
     * Undoes {@code onOrderPlaced}, whatever ended the checkout: Inventory gives back any stock it holds for the order,
     * and the order is rejected with the reason the customer sees.
     */
    @SagaCompensation(forStep = "onOrderPlaced")
    public void cancelCheckout(CompensationTrigger trigger) {
        Order.RejectionReason reason;
        if (trigger.kind() == CompensationTriggerKind.DEADLINE_EXPIRED) {
            reason = Order.RejectionReason.PAYMENT_TIMED_OUT;
        } else if ("onStockUnavailable".equals(trigger.stepName())) {
            reason = Order.RejectionReason.OUT_OF_STOCK;
        } else {
            reason = Order.RejectionReason.PAYMENT_NOT_AUTHORIZED;
        }
        commandGateway.send(new ReleaseStockCommand(orderId()));
        commandGateway.send(new RejectOrderCommand(orderId(), reason));
    }

    private String orderId() {
        return context.getState("orderId", String.class).orElseThrow();
    }

    /** Kept as its plain string: durable storage keeps {@code SagaContext} values as JSON scalars. */
    private BigDecimal amount() {
        return new BigDecimal(context.getState("amount", String.class).orElseThrow());
    }
}
