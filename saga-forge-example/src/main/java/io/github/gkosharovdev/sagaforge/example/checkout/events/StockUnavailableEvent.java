package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.StockReservation;

/**
 * Inventory could not hold an item of the order, so it holds none of them. A business failure of
 * the checkout.
 */
@DomainEvent(aggregate = StockReservation.class)
public record StockUnavailableEvent(
    @AggregateIdentifier String reservationId, String orderId, String sku) {}
