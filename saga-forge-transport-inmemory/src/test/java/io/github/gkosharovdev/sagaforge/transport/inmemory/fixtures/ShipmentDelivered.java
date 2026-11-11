package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Ends {@link DeliverySaga}. */
@DomainEvent(aggregate = Shipment.class)
public record ShipmentDelivered(@AggregateIdentifier String orderId) {
}
