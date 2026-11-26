package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Fan-out fixture event (specs/014-publish-once Property 47). */
@DomainEvent(aggregate = Shipment.class)
public record LabelPrinted(@AggregateIdentifier String orderId) {}
