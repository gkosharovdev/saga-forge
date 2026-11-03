package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Invalid: a {@code @DomainEvent} with two {@code @AggregateIdentifier} fields (spec FR-014).
 */
@DomainEvent(aggregate = ValidAggregate.class)
public record EventWithTwoIdentifiers(@AggregateIdentifier String id, @AggregateIdentifier String otherId) {
}
