package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * A fully valid aggregate: exactly one {@code @AggregateIdentifier}, an instance-method handler whose command
 * carries an identifier, and a constructor (creation) handler whose command does not need one. It extends and
 * implements no Saga Forge type (spec FR-013).
 */
@Aggregate
public class ValidAggregate {

    @AggregateIdentifier
    private String id;

    private int total;

    @CommandHandler
    public ValidAggregate(CreateValidAggregate command) {
        this.id = command.name();
    }

    @CommandHandler
    public void handle(ValidAggregateCommand command) {
        this.total += command.amount();
    }
}
