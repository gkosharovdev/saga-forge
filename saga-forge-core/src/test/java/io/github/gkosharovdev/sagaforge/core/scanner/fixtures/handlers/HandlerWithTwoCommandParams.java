package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * Invalid: a {@code @CommandHandler} with two {@code @Command}-typed parameters (spec FR-009). Both commands
 * are otherwise valid.
 */
public class HandlerWithTwoCommandParams {

    @CommandHandler
    public void handle(FirstCommand first, SecondCommand second) {
    }
}
