package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;

/**
 * The single, well-formed loader and saver for {@link ValidAggregate}.
 */
public class ValidAggregateRepository {

    @AggregateLoader
    public ValidAggregate load(String id) {
        return null;
    }

    @AggregateSaver
    public void save(ValidAggregate aggregate) {
    }
}
