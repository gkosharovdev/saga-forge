package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Starts {@link DeliverySaga}; published by the test. */
@DomainEvent(aggregate = Order.class)
public record OrderPlaced(@AggregateIdentifier String orderId, long amount) {
}
