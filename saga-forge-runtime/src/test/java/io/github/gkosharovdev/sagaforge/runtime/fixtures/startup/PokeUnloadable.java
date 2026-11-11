package io.github.gkosharovdev.sagaforge.runtime.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Handled by {@link UnloadableAggregate}, which nothing can load. */
@Command
public record PokeUnloadable(@AggregateIdentifier String unloadableId) {
}
