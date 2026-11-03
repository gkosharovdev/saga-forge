package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Recorded by the {@link Booking#Booking(ConfirmBooking) Booking} creation handler. The booking id is both the
 * identifier and the saga's correlation value.
 *
 * @param bookingId the {@link Booking} created and the saga's association value
 * @param claimId   the availability claim the booking holds
 * @param paymentId the confirmed payment
 */
@DomainEvent(aggregate = Booking.class)
public record BookingConfirmedEvent(@AggregateIdentifier String bookingId, String claimId, String paymentId) {
}
