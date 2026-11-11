package io.github.gkosharovdev.sagaforge.example.spring.events;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.spring.Payment;

/** Payment was taken for an order; {@code orderId} is the saga's association property. */
@DomainEvent(aggregate = Payment.class)
public record PaymentProcessedEvent(@AggregateIdentifier String paymentId, String orderId, BigDecimal amount) {
}
