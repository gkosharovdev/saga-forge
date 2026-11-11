package io.github.gkosharovdev.sagaforge.example.spring;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.example.spring.commands.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.example.spring.events.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.example.spring.events.StockUnavailableEvent;

/**
 * The order aggregate, mapped for Spring Data JDBC. It implements no Saga Forge type and has no loader or saver: the
 * starter loads it through {@link OrderRepository} by the command's {@code @AggregateIdentifier}, runs the handler and
 * saves it, all in the saga's unit of work.
 */
@Aggregate
@Table("orders")
public class Order {

    /** Lifecycle of an order. */
    public enum Status { CREATED, COMPLETED, OUT_OF_STOCK }

    @Id
    @AggregateIdentifier
    private final String orderId;
    private final boolean inStock;
    private Status status;
    @Version
    private Long version;

    @PersistenceCreator
    Order(String orderId, boolean inStock, Status status, Long version) {
        this.orderId = orderId;
        this.inStock = inStock;
        this.status = status;
        this.version = version;
    }

    /** A new order; when {@code inStock} is false, reserving its stock fails after payment. */
    public static Order create(String orderId, boolean inStock) {
        return new Order(orderId, inStock, Status.CREATED, null);
    }

    /**
     * Reserves the stock and completes the order, recording {@link OrderCompletedEvent}; without stock, records
     * {@link StockUnavailableEvent} instead, a business failure the saga answers by refunding the payment.
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
