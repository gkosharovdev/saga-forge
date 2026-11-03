package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;

/**
 * A well-formed loader and saver for {@link ReachedAggregateWithoutIdentifier}, an invalid aggregate. Used to
 * show that aggregate types are reached from loader and saver signatures (and reported once, not per
 * signature).
 */
public class ReachingRepository {

    @AggregateLoader
    public ReachedAggregateWithoutIdentifier load(String id) {
        return null;
    }

    @AggregateSaver
    public void save(ReachedAggregateWithoutIdentifier aggregate) {
    }
}
