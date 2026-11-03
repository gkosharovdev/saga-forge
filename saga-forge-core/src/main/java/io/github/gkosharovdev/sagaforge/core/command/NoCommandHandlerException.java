package io.github.gkosharovdev.sagaforge.core.command;

import java.util.Objects;

/**
 * Thrown when a command is sent but no {@code @CommandHandler} exists for its type.
 *
 * <p>Raised by {@link CommandGateway#send(Object)}. The unit of work in which the command was sent is rolled
 * back. The exception carries the command type so callers and logs can pinpoint the missing handler.
 */
public class NoCommandHandlerException extends RuntimeException {

    private final Class<?> commandType;

    /**
     * Creates a new no-handler exception.
     *
     * @param commandType type of the command that has no handler
     * @throws NullPointerException if {@code commandType} is {@code null}
     */
    public NoCommandHandlerException(Class<?> commandType) {
        super(buildMessage(Objects.requireNonNull(commandType, "commandType")));
        this.commandType = commandType;
    }

    /**
     * @return type of the command that has no handler
     */
    public Class<?> getCommandType() {
        return commandType;
    }

    private static String buildMessage(Class<?> commandType) {
        return "No @CommandHandler found for command " + commandType.getName();
    }
}
