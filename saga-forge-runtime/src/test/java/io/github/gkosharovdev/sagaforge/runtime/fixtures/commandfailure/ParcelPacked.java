package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Runs {@link ParcelSaga#pack}, which sends {@link ShipParcel}. */
@DomainEvent(aggregate = Parcel.class)
public record ParcelPacked(@AggregateIdentifier String parcelId) {
}
