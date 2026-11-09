package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate named by {@link ParcelRequested}. */
@Aggregate
public final class Parcel {

    @AggregateIdentifier
    private String parcelId;
}
