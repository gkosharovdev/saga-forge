package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Recorded by {@link ReservationService} after it reserved items.
 *
 * @param reservationId the {@link Reservation} that changed
 * @param orderId       the saga's correlation value
 * @param quantity      the number of items reserved
 */
@DomainEvent(aggregate = Reservation.class)
public record ItemsReserved(@AggregateIdentifier String reservationId, String orderId, int quantity) {
}
