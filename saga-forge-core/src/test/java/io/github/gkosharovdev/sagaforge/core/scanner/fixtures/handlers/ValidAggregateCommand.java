package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * A valid command handled by an instance method of {@link ValidAggregate}; it names the target aggregate.
 */
@Command
public record ValidAggregateCommand(@AggregateIdentifier String id, int amount) {
}
