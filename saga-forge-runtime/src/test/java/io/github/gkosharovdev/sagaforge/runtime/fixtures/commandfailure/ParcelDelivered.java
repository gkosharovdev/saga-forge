package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Ends {@link ParcelSaga}. */
@DomainEvent(aggregate = Parcel.class)
public record ParcelDelivered(@AggregateIdentifier String parcelId) {
}
