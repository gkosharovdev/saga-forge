package io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Sent by {@code onPaymentDeclined}; handled by the instance method
 * {@link Inventory#handle(ReleaseAvailability)}, so it names its target aggregate with
 * {@code @AggregateIdentifier}.
 *
 * @param inventoryId the {@link Inventory} to load and change
 * @param bookingId   the saga's correlation value
 * @param claimId     the claim to release, read from {@code SagaContext}
 */
@Command
public record ReleaseAvailability(@AggregateIdentifier String inventoryId, String bookingId, String claimId) {
}
