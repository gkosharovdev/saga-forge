package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link Label}; no step reacts to it, so it is handed off after commit. */
@DomainEvent(aggregate = Label.class)
public record LabelPrinted(@AggregateIdentifier String labelId, String parcelId) {
}
