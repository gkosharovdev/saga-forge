package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Runs {@link ParcelSaga#pay}. */
@DomainEvent(aggregate = Parcel.class)
public record ParcelPaid(@AggregateIdentifier String parcelId) {
}
