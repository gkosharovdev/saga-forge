package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** The payment aggregate {@code PaymentProcessedEvent} belongs to. */
@Aggregate
public class Payment {

    @AggregateIdentifier
    private String paymentId;
}
