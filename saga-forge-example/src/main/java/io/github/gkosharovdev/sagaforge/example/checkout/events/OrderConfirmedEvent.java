package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Order;
import io.github.gkosharovdev.sagaforge.example.checkout.OrderLine;
import java.util.List;

/**
 * The order is confirmed: its stock is held and its payment authorised. Recorded by {@link Order}
 * at the end of {@code CheckoutSaga}, and starts {@code FulfilmentSaga}. It carries what fulfilment
 * needs, so the two sagas share no state.
 */
@DomainEvent(aggregate = Order.class)
public record OrderConfirmedEvent(
    @AggregateIdentifier String orderId,
    List<OrderLine> lines,
    String deliveryAddress,
    String reservationId,
    String paymentId) {}
