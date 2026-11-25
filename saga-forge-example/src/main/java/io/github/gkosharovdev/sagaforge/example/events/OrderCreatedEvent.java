package io.github.gkosharovdev.sagaforge.example.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.Order;
import java.math.BigDecimal;

/** An order was placed. Starts {@code OrderProcessingSaga}. */
@DomainEvent(aggregate = Order.class)
public record OrderCreatedEvent(@AggregateIdentifier String orderId, BigDecimal amount) {}
