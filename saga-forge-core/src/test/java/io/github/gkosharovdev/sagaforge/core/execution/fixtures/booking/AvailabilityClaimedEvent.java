package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Recorded by {@link Inventory#handle(ClaimAvailability)} after it claimed availability.
 *
 * @param inventoryId the {@link Inventory} that changed
 * @param bookingId   the saga's correlation value
 * @param claimId     the claim created, see {@link Inventory#claimIdFor(String)}
 */
@DomainEvent(aggregate = Inventory.class)
public record AvailabilityClaimedEvent(@AggregateIdentifier String inventoryId, String bookingId, String claimId) {
}
