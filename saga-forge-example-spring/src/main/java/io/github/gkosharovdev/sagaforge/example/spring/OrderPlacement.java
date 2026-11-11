package io.github.gkosharovdev.sagaforge.example.spring;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.example.spring.events.OrderCreatedEvent;

/**
 * Application code that places an order: saves it and publishes {@link OrderCreatedEvent} in one transaction, so the
 * event is stored in the message store together with the order and the saga starts once both committed.
 */
@Service
public class OrderPlacement {

    private final OrderRepository orders;
    private final EventPublisher events;

    public OrderPlacement(OrderRepository orders, EventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    @Transactional
    public void place(String orderId, BigDecimal amount, boolean inStock) {
        orders.save(Order.create(orderId, inStock));
        events.publish(new OrderCreatedEvent(orderId, amount));
    }
}
