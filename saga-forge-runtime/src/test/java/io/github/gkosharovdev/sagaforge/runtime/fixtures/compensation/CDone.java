package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Runs step C of {@link UnwindSaga}. */
@DomainEvent(aggregate = Order.class)
public record CDone(@AggregateIdentifier String orderId) {
}
