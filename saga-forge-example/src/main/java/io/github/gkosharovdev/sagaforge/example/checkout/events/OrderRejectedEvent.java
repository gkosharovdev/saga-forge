package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Order;

/** The order was rejected at checkout; for the customer's notification, not for a saga. */
@DomainEvent(aggregate = Order.class)
public record OrderRejectedEvent(@AggregateIdentifier String orderId, Order.RejectionReason reason) {
}
