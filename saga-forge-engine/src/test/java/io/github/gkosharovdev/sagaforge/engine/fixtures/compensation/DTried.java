package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Runs step D of {@link UnwindSaga}. */
@DomainEvent(aggregate = Order.class)
public record DTried(@AggregateIdentifier String orderId) {
}
