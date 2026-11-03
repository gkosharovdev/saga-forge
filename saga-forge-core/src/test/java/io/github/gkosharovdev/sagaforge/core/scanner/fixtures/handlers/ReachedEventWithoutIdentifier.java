package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Invalid: a {@code @DomainEvent} with no {@code @AggregateIdentifier}, never passed to {@code validateAll}
 * directly — it is reached only from {@link ReachingSaga}'s step signature.
 */
@DomainEvent(aggregate = ValidAggregate.class)
public record ReachedEventWithoutIdentifier(String id) {
}
