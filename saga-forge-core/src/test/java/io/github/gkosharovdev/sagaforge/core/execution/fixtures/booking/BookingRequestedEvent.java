package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import java.time.LocalDate;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Start event of {@code CreateBookingSaga}, published from outside any saga unit of work (for example by a
 * request handler through {@code EventPublisher}). It names {@link Booking} as its aggregate because it is about
 * that booking, even though the {@link Booking} aggregate itself is only created later by {@link ConfirmBooking}:
 * naming an aggregate type does not require an instance to exist, and an externally published event is never
 * counted as a step's touched aggregate. The booking id is both the identifier and the saga's correlation value.
 *
 * @param bookingId   the booking's id and the saga's association value
 * @param inventoryId the {@link Inventory} to claim availability from
 * @param checkIn     first night of the stay
 * @param checkOut    departure day (exclusive)
 * @param amount      the amount to charge, lifted into {@code SagaContext} by the start step
 */
@DomainEvent(aggregate = Booking.class)
public record BookingRequestedEvent(
        @AggregateIdentifier String bookingId,
        String inventoryId,
        LocalDate checkIn,
        LocalDate checkOut,
        long amount) {
}
