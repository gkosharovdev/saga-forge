package io.github.gkosharovdev.sagaforge.example.checkout;

import java.math.BigDecimal;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CancelOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ConfirmOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.RejectOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderCancelledEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderConfirmedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderPlacedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderRejectedEvent;

/**
 * The order, owned by the Ordering service that runs both sagas. A plain class: it extends and implements no Saga
 * Forge type. It is the only aggregate in this example that lives in the saga's own database; Inventory, Payments and
 * Logistics are other services, reached through commands and heard from through events.
 */
@Aggregate
public class Order {

    /** Lifecycle of an order. */
    public enum Status { PLACED, CONFIRMED, REJECTED, CANCELLED }

    /** Why checkout turned an order down; shown to the customer. */
    public enum RejectionReason { OUT_OF_STOCK, PAYMENT_NOT_AUTHORIZED, PAYMENT_TIMED_OUT }

    @AggregateIdentifier
    private final String orderId;

    private final List<OrderLine> lines;
    private final BigDecimal amount;
    private final String deliveryAddress;
    private final boolean express;
    private Status status = Status.PLACED;

    /** An order with standard checkout. */
    public Order(String orderId, List<OrderLine> lines, BigDecimal amount, String deliveryAddress) {
        this(orderId, lines, amount, deliveryAddress, false);
    }

    /** An order with standard or, if {@code express}, express checkout. */
    public Order(String orderId, List<OrderLine> lines, BigDecimal amount, String deliveryAddress, boolean express) {
        this.orderId = orderId;
        this.lines = List.copyOf(lines);
        this.amount = amount;
        this.deliveryAddress = deliveryAddress;
        this.express = express;
    }

    /** The event the application publishes once the order is saved; it starts {@code CheckoutSaga}. */
    public OrderPlacedEvent placed() {
        return new OrderPlacedEvent(orderId, lines, amount, deliveryAddress, express);
    }

    /** Confirms the order and records {@link OrderConfirmedEvent}, which starts {@code FulfilmentSaga}. */
    @CommandHandler
    public void handle(ConfirmOrderCommand command) {
        requireStatus(Status.PLACED);
        status = Status.CONFIRMED;
        DomainEvents.record(new OrderConfirmedEvent(orderId, lines, deliveryAddress, command.reservationId(),
                command.paymentId()));
    }

    @CommandHandler
    public void handle(RejectOrderCommand command) {
        requireStatus(Status.PLACED);
        status = Status.REJECTED;
        DomainEvents.record(new OrderRejectedEvent(orderId, command.reason()));
    }

    @CommandHandler
    public void handle(CancelOrderCommand command) {
        requireStatus(Status.CONFIRMED);
        status = Status.CANCELLED;
        DomainEvents.record(new OrderCancelledEvent(orderId, command.reason()));
    }

    public String orderId() {
        return orderId;
    }

    public Status status() {
        return status;
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Order " + orderId + " is " + status + ", not " + expected);
        }
    }
}
