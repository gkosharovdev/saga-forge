package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Shipment;

/** The customer received the parcel. Published by the Logistics service. */
@DomainEvent(aggregate = Shipment.class)
public record ShipmentDeliveredEvent(@AggregateIdentifier String shipmentId, String orderId) {
}
