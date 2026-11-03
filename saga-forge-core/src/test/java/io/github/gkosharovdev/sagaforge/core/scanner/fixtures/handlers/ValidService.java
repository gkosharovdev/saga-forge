package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * A valid service-hosted handler for {@link ValidServiceCommand}.
 */
public class ValidService {

    @CommandHandler
    public void handle(ValidServiceCommand command) {
    }
}
