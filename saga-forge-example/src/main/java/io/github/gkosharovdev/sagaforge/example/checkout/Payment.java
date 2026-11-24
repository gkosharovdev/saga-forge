package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * A card payment held by the payment provider, fronted by the Payments context. Its state lives in that other service; the class only names the aggregate its events belong to.
 */
@Aggregate
public class Payment {

    @AggregateIdentifier
    private String paymentId;
}
