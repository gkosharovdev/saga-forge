package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate of {@link HoldSaga}. */
@Aggregate
public final class Hold {

  @AggregateIdentifier private String holdId;
}
