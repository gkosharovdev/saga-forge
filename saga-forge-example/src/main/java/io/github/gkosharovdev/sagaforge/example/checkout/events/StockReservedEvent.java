package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.StockReservation;

/** Inventory holds the order's items. Published by the Inventory service. */
@DomainEvent(aggregate = StockReservation.class)
public record StockReservedEvent(@AggregateIdentifier String reservationId, String orderId) {
}
