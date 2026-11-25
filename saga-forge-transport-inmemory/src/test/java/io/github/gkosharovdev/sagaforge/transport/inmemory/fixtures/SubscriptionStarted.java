package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Starts {@link RenewalSaga}.
 *
 * @param subscriptionId the subscription, also the invoice its renewal charges
 * @param chargeIn ISO-8601 duration from now to the renewal charge; may be negative
 */
@DomainEvent(aggregate = Subscription.class)
public record SubscriptionStarted(@AggregateIdentifier String subscriptionId, String chargeIn) {}
