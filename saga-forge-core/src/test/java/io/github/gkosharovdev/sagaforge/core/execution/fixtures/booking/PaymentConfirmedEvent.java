package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * The payment provider's "confirmed" callback. Arrives through the transport, outside any saga unit of work;
 * no fixture handler records it, tests deliver it.
 *
 * @param paymentId the {@link Payment} confirmed
 * @param bookingId the saga's correlation value
 */
@DomainEvent(aggregate = Payment.class)
public record PaymentConfirmedEvent(@AggregateIdentifier String paymentId, String bookingId) {
}
