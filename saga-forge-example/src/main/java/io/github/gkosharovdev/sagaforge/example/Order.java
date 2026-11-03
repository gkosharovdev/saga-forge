package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.events.OrderCompletedEvent;

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
    public enum Status { CREATED, COMPLETED }

    @AggregateIdentifier
    private final String orderId;

    private Status status = Status.CREATED;

    public Order(String orderId) {
        this.orderId = orderId;
    }

    /**
     * Reserves the order's stock and completes it, recording {@link OrderCompletedEvent}.
     *
     * @throws IllegalStateException if the order is already completed
     */
    @CommandHandler
    public void handle(ReserveStockCommand command) {
        if (status != Status.CREATED) {
            throw new IllegalStateException("Order " + orderId + " is already " + status);
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
