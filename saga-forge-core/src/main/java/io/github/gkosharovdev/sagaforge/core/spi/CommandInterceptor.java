package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * SPI that sees every command the engine is about to handle, and may fail it, complete it without its handler, or
 * let it proceed ({@code specs/009-saga-test-fixture} contracts/command-interceptor.md, Property 36).
 *
 * <p><strong>Implemented by host integrations and test fixtures, never by client code.</strong> The saga test
 * fixture uses it to record commands, to answer commands whose handler a test did not register, and to make commands
 * fail. At most one is registered with an engine; the default {@link #proceeding()} leaves behaviour unchanged.</p>
 *
 * <h3>Obligations of the engine</h3>
 * <ol>
 *   <li>{@link #intercept} is called exactly once per handling of a command: a command sent from a step or a
 *       compensation, a command sent outside a unit of work, and an outbox command delivered through
 *       {@link CommandReceiver}. A command whose identity already committed for the instance is skipped and not
 *       handed over. An outbox command is handed over when it is delivered, not when it is stored.</li>
 *   <li>It runs on the handling thread, inside the unit of work's transaction, with the command's identity bound.</li>
 *   <li>An exception thrown by {@code intercept} is handled exactly as an exception of the handler: the transaction is
 *       marked rollback-only, the failure is classified, and retry and compensation follow.</li>
 *   <li>Returning without {@link CommandInvocation#proceed()} completes the command as handled; its identity is
 *       recorded with the step as for a handled command.</li>
 * </ol>
 */
@FunctionalInterface
public interface CommandInterceptor {

    /**
     * Intercepts one handling of a command.
     *
     * @param invocation the command, its identity and its handler route; never {@code null}
     */
    void intercept(CommandInvocation invocation);

    /** The default interceptor: runs every command's handler, so an engine behaves as without an interceptor. */
    static CommandInterceptor proceeding() {
        return CommandInvocation::proceed;
    }
}
