package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Ends {@link UnwindSaga}. */
@DomainEvent(aggregate = Order.class)
public record Finished(@AggregateIdentifier String orderId) {
}
