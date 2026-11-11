package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** An event no saga reacts to. */
@DomainEvent(aggregate = Order.class)
public record Unrouted(@AggregateIdentifier String orderId) {
}
