package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * Inbound entry point for logged commands ({@code @Command(delivery = LOGGED)}): the counterpart of
 * {@link EventReceiver} for commands a transport delivers from its message log ({@code specs/007-in-memory-transport}
 * research §3).
 *
 * <p><strong>Implemented by the engine</strong> ({@code SagaEngine.commandReceiver()}); called by transports.</p>
 *
 * <h3>Effect</h3>
 * <p>Synchronously, on the calling thread: opens a new non-saga unit of work, runs the single {@code @CommandHandler}
 * for the command's type with {@link LoggedCommand#commandId()} bound for a {@code CommandId} parameter, then calls
 * {@link LoggedCommand#inTransaction()} with that unit of work's transaction, then commits. Events the handler records
 * go to the {@link Outbox} as for any non-saga unit of work. If anything throws, the unit of work rolls back and the
 * exception propagates; the transport redelivers.</p>
 *
 * <h3>Failures</h3>
 * <ul>
 *   <li>{@link IllegalArgumentException} for a {@code null} delivery or a command whose class is not
 *       {@code @Command}-annotated.</li>
 *   <li>{@link IllegalStateException} if the engine has not started or a unit of work is running on this thread.</li>
 *   <li>{@code NoCommandHandlerException} if no handler exists for the command's type.</li>
 *   <li>Any {@link RuntimeException} the handler, an aggregate loader or saver, or {@code inTransaction} threw,
 *       after rollback.</li>
 * </ul>
 */
public interface CommandReceiver {

    /**
     * Handles one logged command in a unit of work of its own.
     *
     * @param delivery the command, its identity and the in-transaction callback; must not be {@code null}
     */
    void receive(LoggedCommand delivery);
}
