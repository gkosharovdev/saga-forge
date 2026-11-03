package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * The payment aggregate that {@code PaymentProcessedEvent} belongs to. Its state lives with the payment
 * provider; {@link PaymentService} is the domain service fronting it, so the example only needs its identity.
 */
@Aggregate
public class Payment {

    @AggregateIdentifier
    private String paymentId;
}
