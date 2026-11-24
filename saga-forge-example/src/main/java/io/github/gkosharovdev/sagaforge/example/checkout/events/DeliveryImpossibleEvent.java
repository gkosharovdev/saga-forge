package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Shipment;

/**
 * Logistics gave up on the order: no courier serves the address, or its delivery attempts ran out and the parcel went
 * back to the warehouse. Logistics owns the attempts and their timing; the saga only learns the result. A business
 * failure of the fulfilment.
 */
@DomainEvent(aggregate = Shipment.class)
public record DeliveryImpossibleEvent(@AggregateIdentifier String shipmentId, String orderId, String reason) {
}
