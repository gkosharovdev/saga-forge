package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Starts {@link FulfilmentSaga}; published from outside any unit of work. */
@DomainEvent(aggregate = Order.class)
public record OrderPlaced(@AggregateIdentifier String orderId) {
}
