package io.github.gkosharovdev.sagaforge.engine.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Handled by {@link UnloadableAggregate}, which nothing can load. */
@Command
public record PokeUnloadable(@AggregateIdentifier String unloadableId) {
}
