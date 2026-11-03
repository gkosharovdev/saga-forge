package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * A valid domain event about {@link ValidAggregate}, with exactly one {@code @AggregateIdentifier}.
 */
@DomainEvent(aggregate = ValidAggregate.class)
public record ValidEvent(@AggregateIdentifier String id) {
}
