package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Event of the retry fixtures. */
@DomainEvent(aggregate = Delivery.class)
public record DeliveryBooked(@AggregateIdentifier String bookingId, long amount) {
}
