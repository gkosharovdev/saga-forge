package io.github.gkosharovdev.sagaforge.example.checkout.events;

import java.math.BigDecimal;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Order;
import io.github.gkosharovdev.sagaforge.example.checkout.OrderLine;

/**
 * The customer placed an order at checkout. Starts {@code CheckoutSaga}.
 *
 * @param express whether the customer chose express checkout, which shortens the payment window
 */
@DomainEvent(aggregate = Order.class)
public record OrderPlacedEvent(@AggregateIdentifier String orderId, List<OrderLine> lines, BigDecimal amount,
                               String deliveryAddress, boolean express) {
}
