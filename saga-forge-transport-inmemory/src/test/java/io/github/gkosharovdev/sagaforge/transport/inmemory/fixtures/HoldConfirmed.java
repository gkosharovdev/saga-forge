package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Ends {@link HoldSaga} before its deadline. */
@DomainEvent(aggregate = Hold.class)
public record HoldConfirmed(@AggregateIdentifier String holdId) {
}
