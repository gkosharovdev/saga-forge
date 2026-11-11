package io.github.gkosharovdev.sagaforge.runtime.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link Slot}; runs the {@code @EndSaga} step in-process. */
@DomainEvent(aggregate = Slot.class)
public record SlotReserved(@AggregateIdentifier String slotId, String parcelId) {
}
