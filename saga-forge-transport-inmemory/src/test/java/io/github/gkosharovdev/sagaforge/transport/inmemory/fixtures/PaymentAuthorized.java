package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The payment module authorized a payment; an intermediate step of {@link FulfilmentSaga}. */
@DomainEvent(aggregate = Payment.class)
public record PaymentAuthorized(@AggregateIdentifier String orderId) {
}
