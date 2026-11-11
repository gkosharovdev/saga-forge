package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * A {@code @Command(delivery = LOGGED)} command sent in a unit of work, as carried by {@link OutgoingMessages}
 * ({@code specs/007-in-memory-transport} research §3).
 *
 * @param commandId the identity assigned when the command was sent; non-null
 * @param command   the command; non-null
 * @throws IllegalArgumentException if a component is {@code null}
 */
public record OutgoingCommand(CommandId commandId, Object command) {

    public OutgoingCommand {
        if (commandId == null) {
            throw new IllegalArgumentException("commandId must not be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
    }
}
