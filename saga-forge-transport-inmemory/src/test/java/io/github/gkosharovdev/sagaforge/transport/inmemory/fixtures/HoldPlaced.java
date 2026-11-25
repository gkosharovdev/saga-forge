package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Starts {@link HoldSaga}. */
@DomainEvent(aggregate = Hold.class)
public record HoldPlaced(@AggregateIdentifier String holdId) {
}
