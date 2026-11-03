package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Invalid: a {@code @DomainEvent} with no {@code @AggregateIdentifier} field (spec FR-014).
 */
@DomainEvent(aggregate = ValidAggregate.class)
public record EventWithoutIdentifier(String id) {
}
