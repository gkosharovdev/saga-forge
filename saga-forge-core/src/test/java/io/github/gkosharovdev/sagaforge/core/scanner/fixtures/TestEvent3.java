package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * A third domain event fixture for scanner tests.
 */
@DomainEvent
public record TestEvent3(String orderId) {
}
