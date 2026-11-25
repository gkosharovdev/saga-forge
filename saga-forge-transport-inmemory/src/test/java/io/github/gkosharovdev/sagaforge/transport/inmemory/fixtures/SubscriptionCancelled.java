package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Ends {@link RenewalSaga}, cancelling the scheduled charge. */
@DomainEvent(aggregate = Subscription.class)
public record SubscriptionCancelled(@AggregateIdentifier String subscriptionId) {}
