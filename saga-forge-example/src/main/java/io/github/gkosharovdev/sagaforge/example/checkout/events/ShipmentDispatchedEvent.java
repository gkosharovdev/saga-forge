package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Shipment;

/** The parcel has left the warehouse with a courier. Published by the Logistics service. */
@DomainEvent(aggregate = Shipment.class)
public record ShipmentDispatchedEvent(@AggregateIdentifier String shipmentId, String orderId) {
}
