package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;

/**
 * Invalid: an {@code @Aggregate} with no {@code @AggregateIdentifier}, never passed to {@code validateAll}
 * directly — it is reached only from {@link ReachingRepository}'s loader and saver signatures.
 */
@Aggregate
public class ReachedAggregateWithoutIdentifier {
}
