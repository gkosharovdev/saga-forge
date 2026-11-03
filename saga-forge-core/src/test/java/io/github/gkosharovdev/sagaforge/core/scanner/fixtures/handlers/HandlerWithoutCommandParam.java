package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * Invalid: a {@code @CommandHandler} with zero {@code @Command}-typed parameters (spec FR-009).
 */
public class HandlerWithoutCommandParam {

    @CommandHandler
    public void handle(String notACommand) {
    }
}
