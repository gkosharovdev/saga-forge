package io.github.gkosharovdev.sagaforge.runtime.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Event of {@link SagaWithoutNoArgConstructor}. */
@DomainEvent(aggregate = StartupThing.class)
public record ThingEnded(@AggregateIdentifier String thingId) {
}
