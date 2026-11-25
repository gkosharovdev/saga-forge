package io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** An order event of {@link PaymentWindowSaga}. */
@DomainEvent(aggregate = Order.class)
public record PaymentAuthorized(@AggregateIdentifier String orderId) {}
