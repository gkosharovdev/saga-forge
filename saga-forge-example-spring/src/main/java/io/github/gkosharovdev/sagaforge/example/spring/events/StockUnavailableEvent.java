package io.github.gkosharovdev.sagaforge.example.spring.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.spring.Order;

/** The stock could not be reserved after payment: the saga's business failure, answered by a refund. */
@DomainEvent(aggregate = Order.class)
public record StockUnavailableEvent(@AggregateIdentifier String orderId) {
}
