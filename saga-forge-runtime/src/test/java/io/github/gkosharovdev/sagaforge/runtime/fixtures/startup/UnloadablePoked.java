package io.github.gkosharovdev.sagaforge.runtime.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link UnloadableAggregate}. */
@DomainEvent(aggregate = UnloadableAggregate.class)
public record UnloadablePoked(@AggregateIdentifier String unloadableId) {
}
