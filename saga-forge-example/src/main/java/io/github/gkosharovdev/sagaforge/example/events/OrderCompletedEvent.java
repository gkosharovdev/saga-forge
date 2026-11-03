package io.github.gkosharovdev.sagaforge.example.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.Order;

/** The order's stock was reserved and the order completed. Ends {@code OrderProcessingSaga}. */
@DomainEvent(aggregate = Order.class)
public record OrderCompletedEvent(@AggregateIdentifier String orderId) {
}
