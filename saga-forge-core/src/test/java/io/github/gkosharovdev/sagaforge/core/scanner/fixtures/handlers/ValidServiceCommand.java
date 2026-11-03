package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * A valid command handled by {@link ValidService}; a service-handled command needs no
 * {@code @AggregateIdentifier}.
 */
@Command
public record ValidServiceCommand(String reference) {
}
