package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import java.time.LocalDate;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Sent by {@code onBookingRequested}; handled by the instance method {@link Inventory#handle(ClaimAvailability)},
 * so it names its target aggregate with {@code @AggregateIdentifier}.
 *
 * @param inventoryId the {@link Inventory} to load and change
 * @param bookingId   the saga's correlation value
 * @param checkIn     first night of the stay
 * @param checkOut    departure day (exclusive)
 */
@Command
public record ClaimAvailability(
        @AggregateIdentifier String inventoryId,
        String bookingId,
        LocalDate checkIn,
        LocalDate checkOut) {
}
