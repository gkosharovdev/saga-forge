package io.github.gkosharovdev.sagaforge.example.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.Order;

/**
 * The order's stock could not be reserved after it was paid for: a business failure of {@code OrderProcessingSaga},
 * handled by its {@code @StartCompensation} step {@code rejectOrder}, which refunds the payment.
 */
@DomainEvent(aggregate = Order.class)
public record StockUnavailableEvent(@AggregateIdentifier String orderId) {
}
