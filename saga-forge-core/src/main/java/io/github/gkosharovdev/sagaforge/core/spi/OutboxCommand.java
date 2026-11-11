package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.function.Consumer;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * One delivery of a outbox command to {@link CommandReceiver#receive(OutboxCommand)}.
 *
 * @param commandId     the identity assigned when the command was sent; bound for a {@code CommandId} handler
 *                      parameter; non-null
 * @param command       the command; non-null
 * @param inTransaction called once after the handler returned and before the handling unit of work commits, with
 *                      that unit of work's transaction, so a transport can mark the message consumed atomically with
 *                      the handler's effects; non-null
 * @throws IllegalArgumentException if a component is {@code null}
 */
public record OutboxCommand(CommandId commandId, Object command, Consumer<LocalTransaction> inTransaction) {

    public OutboxCommand {
        if (commandId == null) {
            throw new IllegalArgumentException("commandId must not be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (inTransaction == null) {
            throw new IllegalArgumentException("inTransaction must not be null");
        }
    }
}
