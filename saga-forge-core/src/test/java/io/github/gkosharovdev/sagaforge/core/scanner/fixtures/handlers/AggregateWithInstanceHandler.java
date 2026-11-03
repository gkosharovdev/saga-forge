package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * A valid aggregate whose instance-method handler takes
 * {@link AggregateInstanceHandlerCommandWithoutIdentifier}, a command lacking the identifier needed to load
 * the target aggregate.
 */
@Aggregate
public class AggregateWithInstanceHandler {

    @AggregateIdentifier
    private String id;

    @CommandHandler
    public void handle(AggregateInstanceHandlerCommandWithoutIdentifier command) {
    }
}
