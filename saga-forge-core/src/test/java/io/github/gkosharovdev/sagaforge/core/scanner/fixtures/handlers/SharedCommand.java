package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * A valid command handled by both {@link DuplicateHandlerA} and {@link DuplicateHandlerB}.
 */
@Command
public record SharedCommand(String reference) {
}
