package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * One of two handlers for {@link SharedCommand}; together with {@link DuplicateHandlerB} it violates
 * spec FR-011 (model error). On its own it is valid.
 */
public class DuplicateHandlerA {

    @CommandHandler
    public void handle(SharedCommand command) {
    }
}
