package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * The payment provider's "declined" callback. Arrives through the transport, outside any saga unit of work;
 * no fixture handler records it, tests deliver it.
 *
 * @param paymentId the {@link Payment} declined
 * @param bookingId the saga's correlation value
 * @param reason    the provider's decline reason
 */
@DomainEvent(aggregate = Payment.class)
public record PaymentDeclinedEvent(@AggregateIdentifier String paymentId, String bookingId, String reason) {
}
