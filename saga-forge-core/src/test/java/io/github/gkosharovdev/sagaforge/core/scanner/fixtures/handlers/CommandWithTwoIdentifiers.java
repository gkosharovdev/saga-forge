package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Invalid: a {@code @Command} with two {@code @AggregateIdentifier} fields (spec FR-014).
 */
@Command
public record CommandWithTwoIdentifiers(@AggregateIdentifier String id, @AggregateIdentifier String otherId) {
}
