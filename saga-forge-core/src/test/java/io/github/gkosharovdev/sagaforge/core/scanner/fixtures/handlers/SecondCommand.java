package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * A valid command, used as the second {@code @Command} parameter of {@link HandlerWithTwoCommandParams}.
 */
@Command
public record SecondCommand(String reference) {
}
