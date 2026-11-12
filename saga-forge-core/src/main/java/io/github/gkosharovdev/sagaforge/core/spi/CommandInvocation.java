package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * One handling of a command, as handed to a {@link CommandInterceptor} ({@code specs/009-saga-test-fixture}
 * contracts/command-interceptor.md).
 *
 * <p><strong>Implemented by the engine.</strong> Valid only during the {@link CommandInterceptor#intercept} call it
 * was passed to, on that thread.</p>
 */
public interface CommandInvocation {

    /** The command about to be handled; never {@code null}. */
    Object command();

    /**
     * The command's identity as the engine assigned it ({@code specs/005-step-retry}); bound on this thread for the
     * duration of the interception, so a {@code CommandId} handler parameter sees the same value.
     */
    CommandId commandId();

    /** {@code true} if a {@code @CommandHandler} route exists for the command's exact type. */
    boolean hasHandler();

    /**
     * Runs the command's handler route: loads and saves the target aggregate, or invokes the service or creation
     * handler. Exceptions of the handler propagate unchanged.
     *
     * @throws io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException if {@link #hasHandler()} is
     *                                                                                 {@code false}
     * @throws IllegalStateException if called more than once
     */
    void proceed();
}
