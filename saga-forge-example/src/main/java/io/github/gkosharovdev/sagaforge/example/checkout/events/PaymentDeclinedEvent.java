package io.github.gkosharovdev.sagaforge.example.checkout.events;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Payment;

/** The payment provider refused to authorise the amount. A business failure of the checkout. */
@DomainEvent(aggregate = Payment.class)
public record PaymentDeclinedEvent(
    @AggregateIdentifier String paymentId, String orderId, String reason) {}
