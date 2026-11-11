package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** The aggregate the delivery events belong to; it has no handlers. */
@Aggregate
public final class Delivery {

    @AggregateIdentifier
    private String bookingId;
}
