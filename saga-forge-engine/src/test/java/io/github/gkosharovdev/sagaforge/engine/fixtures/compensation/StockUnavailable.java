package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The warehouse cannot supply the order: a business failure of {@link DeliverySaga}. */
@DomainEvent(aggregate = Order.class)
public record StockUnavailable(@AggregateIdentifier String orderId) {
}
