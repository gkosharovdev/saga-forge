package io.github.gkosharovdev.sagaforge.engine.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** A business-failure event of {@link InvalidCompensationSaga}. */
@DomainEvent(aggregate = StartupThing.class)
public record ThingRejected(@AggregateIdentifier String thingId) {
}
