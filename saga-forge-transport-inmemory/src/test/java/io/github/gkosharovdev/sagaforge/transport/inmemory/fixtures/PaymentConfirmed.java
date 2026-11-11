package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** The payment module confirms a payment; ends {@link FulfilmentSaga}. */
@DomainEvent(aggregate = Payment.class)
public record PaymentConfirmed(@AggregateIdentifier String orderId) {
}
