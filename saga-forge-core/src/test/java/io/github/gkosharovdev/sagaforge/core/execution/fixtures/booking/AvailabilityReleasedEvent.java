package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Recorded by {@link Inventory#handle(ReleaseAvailability)} after it released a claim.
 *
 * @param inventoryId the {@link Inventory} that changed
 * @param bookingId   the saga's correlation value
 * @param claimId     the claim released
 */
@DomainEvent(aggregate = Inventory.class)
public record AvailabilityReleasedEvent(@AggregateIdentifier String inventoryId, String bookingId, String claimId) {
}
