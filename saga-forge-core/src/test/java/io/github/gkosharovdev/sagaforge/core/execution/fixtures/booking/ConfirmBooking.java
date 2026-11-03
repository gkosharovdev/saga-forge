package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Creation command sent by {@code onPaymentConfirmed}; handled by the {@link Booking#Booking(ConfirmBooking)
 * Booking} constructor. No aggregate is loaded, so it declares no {@code @AggregateIdentifier}.
 *
 * @param bookingId the id of the {@link Booking} to create and the saga's correlation value
 * @param claimId   the availability claim the booking holds
 * @param paymentId the confirmed payment
 */
@Command
public record ConfirmBooking(String bookingId, String claimId, String paymentId) {
}
