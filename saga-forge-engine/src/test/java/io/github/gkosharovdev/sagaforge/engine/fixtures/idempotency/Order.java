package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** The aggregate the order events belong to; it has no handlers. */
@Aggregate
public final class Order {

    @AggregateIdentifier
    private String orderId;
}
