package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Order;

/** A confirmed order was cancelled because it could not be delivered. */
@DomainEvent(aggregate = Order.class)
public record OrderCancelledEvent(@AggregateIdentifier String orderId, String reason) {}
