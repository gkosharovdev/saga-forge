package io.github.gkosharovdev.sagaforge.example.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.Payment;
import java.math.BigDecimal;

/**
 * Payment was taken for an order. Belongs to the {@link Payment} aggregate; {@code orderId} is the
 * saga's association property, which need not be the event's aggregate identifier.
 */
@DomainEvent(aggregate = Payment.class)
public record PaymentProcessedEvent(
    @AggregateIdentifier String paymentId, String orderId, BigDecimal amount) {}
