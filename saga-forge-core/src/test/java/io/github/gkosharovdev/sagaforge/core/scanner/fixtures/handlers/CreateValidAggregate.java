package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * A valid creation command handled by a constructor of {@link ValidAggregate}; it needs no
 * {@code @AggregateIdentifier}.
 */
@Command
public record CreateValidAggregate(String name) {
}
