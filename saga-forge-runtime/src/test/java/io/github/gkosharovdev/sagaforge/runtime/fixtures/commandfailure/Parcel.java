package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** The aggregate the parcel events belong to; it has no handlers. */
@Aggregate
public final class Parcel {

  @AggregateIdentifier private String parcelId;
}
