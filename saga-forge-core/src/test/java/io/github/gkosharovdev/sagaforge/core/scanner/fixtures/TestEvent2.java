package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * A second domain event fixture for scanner tests.
 */
@DomainEvent
public record TestEvent2(String orderId) {
}
