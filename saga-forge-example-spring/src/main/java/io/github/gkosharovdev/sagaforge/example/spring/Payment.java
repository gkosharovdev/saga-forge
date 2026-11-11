package io.github.gkosharovdev.sagaforge.example.spring;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** The payment aggregate {@code PaymentProcessedEvent} belongs to; its state lives with the payment provider. */
@Aggregate
public class Payment {

    @AggregateIdentifier
    private String paymentId;
}
