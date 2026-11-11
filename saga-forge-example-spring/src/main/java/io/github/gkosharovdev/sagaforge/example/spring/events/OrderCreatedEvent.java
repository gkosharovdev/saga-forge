package io.github.gkosharovdev.sagaforge.example.spring.events;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.spring.Order;

/** An order was placed. Starts {@code OrderProcessingSaga}. */
@DomainEvent(aggregate = Order.class)
public record OrderCreatedEvent(@AggregateIdentifier String orderId, BigDecimal amount) {
}
