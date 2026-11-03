package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * A well-formed service handler whose command, {@link ReachedCommandWithTwoIdentifiers}, is invalid. Used to
 * show that command types are reached from handler signatures.
 */
public class ReachingService {

    @CommandHandler
    public void handle(ReachedCommandWithTwoIdentifiers command) {
    }
}
