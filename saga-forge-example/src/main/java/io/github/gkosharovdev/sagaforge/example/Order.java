package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.events.StockUnavailableEvent;

/**
 * The order aggregate. A plain class: it extends and implements no Saga Forge type, and its persistence
 * lives in {@link OrderRepository}.
 *
 * <p>{@link #handle(ReserveStockCommand)} is an instance-method {@code @CommandHandler}: the engine loads the
 * target order by the command's {@code @AggregateIdentifier} through the {@code @AggregateLoader}, runs the
 * handler, then saves the order through the {@code @AggregateSaver}, all inside the saga's unit of work.</p>
 */
@Aggregate
public class Order {

    /** Lifecycle of an order. */
    public enum Status { CREATED, COMPLETED, OUT_OF_STOCK }

    @AggregateIdentifier
    private final String orderId;

    private final boolean inStock;

    private Status status = Status.CREATED;

    /** An order whose stock can be reserved. */
    public Order(String orderId) {
        this(orderId, true);
    }

    /**
     * @param orderId the order
     * @param inStock whether its stock can be reserved; if not, {@link #handle} records {@link StockUnavailableEvent}
     */
    public Order(String orderId, boolean inStock) {
        this.orderId = orderId;
        this.inStock = inStock;
    }

    /**
     * Reserves the order's stock and completes it, recording {@link OrderCompletedEvent}; if the stock is not
     * available, marks the order {@code OUT_OF_STOCK} and records {@link StockUnavailableEvent} instead, a business
     * failure the saga answers by refunding the payment.
     *
     * @throws IllegalStateException if the order is already completed or out of stock
     */
    @CommandHandler
    public void handle(ReserveStockCommand command) {
        if (status != Status.CREATED) {
            throw new IllegalStateException("Order " + orderId + " is already " + status);
        }
        if (!inStock) {
            status = Status.OUT_OF_STOCK;
            DomainEvents.record(new StockUnavailableEvent(orderId));
            return;
        }
        status = Status.COMPLETED;
        DomainEvents.record(new OrderCompletedEvent(orderId));
    }

    public String orderId() {
        return orderId;
    }

    public Status status() {
        return status;
    }
}
