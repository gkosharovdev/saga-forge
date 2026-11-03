package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * A command with no {@code @AggregateIdentifier}. Valid on its own (a command may declare zero), but invalid
 * as a model once it is handled by the instance method {@link AggregateWithInstanceHandler#handle} (spec
 * FR-014, model error).
 */
@Command
public record AggregateInstanceHandlerCommandWithoutIdentifier(String reference) {
}
