package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Event of {@link ChargeSaga}, published by the test. */
@DomainEvent(aggregate = Order.class)
public record OrderPlaced(@AggregateIdentifier String orderId, long amount) {
}
