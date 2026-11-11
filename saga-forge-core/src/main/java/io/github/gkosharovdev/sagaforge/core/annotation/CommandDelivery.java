package io.github.gkosharovdev.sagaforge.core.annotation;

/**
 * How a {@link Command @Command} type reaches its {@code @CommandHandler} when sent through {@code CommandGateway}
 * ({@code specs/007-in-memory-transport} FR-010, rule R23, Property 35).
 */
public enum CommandDelivery {

    /**
     * The handler runs synchronously inside the sender's unit of work; a handler failure rolls that unit of work
     * back, and retry and compensation apply as for any step failure. The default.
     */
    IN_UNIT_OF_WORK,

    /**
     * {@code send} stores the command in the sender's unit of work's {@code Outbox} together with the
     * {@code CommandId} assigned at send time, and returns. The command is handled after that unit of work commits,
     * in a unit of work of its own, through {@code CommandReceiver}; it is never handled if the sender rolls back.
     * Needs an {@code Outbox} that {@code supportsCommands()}, such as the in-memory transport's.
     */
    LOGGED
}
