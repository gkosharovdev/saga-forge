package io.github.gkosharovdev.sagaforge.example.spring.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.spring.Order;

/** The order's stock was reserved and the order completed. Ends {@code OrderProcessingSaga}. */
@DomainEvent(aggregate = Order.class)
public record OrderCompletedEvent(@AggregateIdentifier String orderId) {
}
