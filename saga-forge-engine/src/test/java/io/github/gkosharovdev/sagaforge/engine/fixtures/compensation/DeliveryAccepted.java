package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The delivery partner accepted the delivery: {@link DeliverySaga} completes. */
@DomainEvent(aggregate = Order.class)
public record DeliveryAccepted(@AggregateIdentifier String orderId) {
}
