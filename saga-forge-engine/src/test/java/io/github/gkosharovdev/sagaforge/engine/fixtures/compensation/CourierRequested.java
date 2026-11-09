package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Asks {@link DeliverySaga} to book a courier through {@link CourierService}. */
@DomainEvent(aggregate = Order.class)
public record CourierRequested(@AggregateIdentifier String orderId) {
}
