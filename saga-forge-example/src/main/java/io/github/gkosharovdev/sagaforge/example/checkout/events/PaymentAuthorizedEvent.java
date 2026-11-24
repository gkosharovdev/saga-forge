package io.github.gkosharovdev.sagaforge.example.checkout.events;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.Payment;

/** The payment provider holds the order's amount on the customer's card. */
@DomainEvent(aggregate = Payment.class)
public record PaymentAuthorizedEvent(@AggregateIdentifier String paymentId, String orderId, BigDecimal amount) {
}
