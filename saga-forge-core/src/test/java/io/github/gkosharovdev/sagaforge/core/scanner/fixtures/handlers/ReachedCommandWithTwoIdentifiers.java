package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Invalid: a {@code @Command} with two {@code @AggregateIdentifier} fields, never passed to
 * {@code validateAll} directly — it is reached only from {@link ReachingService}'s handler signature.
 */
@Command
public record ReachedCommandWithTwoIdentifiers(@AggregateIdentifier String id, @AggregateIdentifier String otherId) {
}
