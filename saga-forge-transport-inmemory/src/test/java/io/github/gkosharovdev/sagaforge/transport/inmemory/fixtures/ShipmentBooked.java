package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link ShippingService} in the fulfilment saga's unit of work; starts {@link DeliverySaga} through the outbox. */
@DomainEvent(aggregate = Shipment.class)
public record ShipmentBooked(@AggregateIdentifier String orderId) {
}
