package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderReadyToShipEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.ReserveStockCommand;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.StockUnavailableEvent;

/** The order aggregate, loaded and saved through {@link OrderRepository}'s loader and saver. */
@Aggregate
public class Order {

    @AggregateIdentifier
    private final String orderId;
    private final boolean inStock;
    private String status = "CREATED";

    public Order(String orderId, boolean inStock) {
        this.orderId = orderId;
        this.inStock = inStock;
    }

    @CommandHandler
    public void handle(ReserveStockCommand command) {
        if (!inStock) {
            status = "OUT_OF_STOCK";
            DomainEvents.record(new StockUnavailableEvent(orderId));
            return;
        }
        status = "COMPLETED";
        DomainEvents.record(new OrderCompletedEvent(orderId));
        DomainEvents.record(new OrderReadyToShipEvent(orderId));
    }

    public String orderId() {
        return orderId;
    }

    public String status() {
        return status;
    }
}
