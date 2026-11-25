package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate of {@link RenewalSaga}. */
@Aggregate
public final class Subscription {

    @AggregateIdentifier
    private String subscriptionId;
}
