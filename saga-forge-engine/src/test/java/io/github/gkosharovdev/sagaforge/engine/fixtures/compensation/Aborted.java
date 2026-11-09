package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The business failure of {@link UnwindSaga}: starts compensation. */
@DomainEvent(aggregate = Order.class)
public record Aborted(@AggregateIdentifier String orderId) {
}
