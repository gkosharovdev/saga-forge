package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * A second domain event fixture for scanner tests.
 */
@DomainEvent(aggregate = TestAggregate.class)
public record TestEvent2(@AggregateIdentifier String orderId) {
}
