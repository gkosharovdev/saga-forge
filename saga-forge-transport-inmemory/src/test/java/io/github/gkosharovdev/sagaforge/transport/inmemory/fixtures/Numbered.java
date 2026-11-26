package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** An event no saga reacts to, numbered within its order so delivery order can be checked. */
@DomainEvent(aggregate = Order.class)
public record Numbered(@AggregateIdentifier String orderId, int sequence) {}
