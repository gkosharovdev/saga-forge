package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The payment of an order was taken; published by the test after {@link Charge}. */
@DomainEvent(aggregate = Order.class)
public record OrderCharged(@AggregateIdentifier String orderId, String paymentId) {
}
