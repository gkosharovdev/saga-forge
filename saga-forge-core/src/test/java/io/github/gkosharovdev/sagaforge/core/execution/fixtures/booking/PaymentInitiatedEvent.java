package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Recorded by {@link PaymentService#handle(InitiatePayment)}. No step of {@code CreateBookingSaga} reacts to it,
 * so it ends the first unit of work and is dispatched through the transport after commit.
 *
 * @param paymentId the {@link Payment} initiated, see {@link PaymentService#paymentIdFor(String)}
 * @param bookingId the saga's correlation value
 * @param amount    the amount to charge
 */
@DomainEvent(aggregate = Payment.class)
public record PaymentInitiatedEvent(@AggregateIdentifier String paymentId, String bookingId, long amount) {
}
