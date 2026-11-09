package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Start event of {@link FailureSaga}. */
@DomainEvent(aggregate = Parcel.class)
public record ParcelRequested(@AggregateIdentifier String parcelId, long weight) {
}
