package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The delivery partner rejected the delivery: a business failure of {@link DeliverySaga}. */
@DomainEvent(aggregate = Order.class)
public record DeliveryRejected(@AggregateIdentifier String orderId, String reason) {
}
