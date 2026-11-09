package io.github.gkosharovdev.sagaforge.engine.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import java.time.Clock;

/** A service handler whose second parameter only a {@code HandlerParameterResolver} can supply (Property 22). */
public final class ServiceWithExtraParameter {

    @CommandHandler
    public void handle(StartupCommand command, Clock clock) {
        // Never invoked: the startup tests only look at the signature.
    }
}
