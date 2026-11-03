package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Sent by {@code onAvailabilityClaimed}; handled by the service {@link PaymentService}, so it needs no
 * {@code @AggregateIdentifier}.
 *
 * @param bookingId the saga's correlation value; the payment id is derived from it
 * @param amount    the amount to charge
 */
@Command
public record InitiatePayment(String bookingId, long amount) {
}
