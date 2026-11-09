package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Sent by {@code onFeeCharged}; handled by the {@link Slot} it names. */
@Command
public record ReserveSlot(@AggregateIdentifier String slotId, String parcelId) {
}
