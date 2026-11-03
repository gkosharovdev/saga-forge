package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Invalid: a {@code @DomainEvent} whose {@code aggregate} is not annotated {@code @Aggregate} (spec FR-014).
 * Its identifier is otherwise well-formed.
 */
@DomainEvent(aggregate = NotAnAggregate.class)
public record EventWithNonAggregate(@AggregateIdentifier String id) {
}
