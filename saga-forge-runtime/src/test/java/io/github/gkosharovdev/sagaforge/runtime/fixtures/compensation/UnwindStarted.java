package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Starts {@link UnwindSaga}: step A. */
@DomainEvent(aggregate = Order.class)
public record UnwindStarted(@AggregateIdentifier String orderId) {
}
