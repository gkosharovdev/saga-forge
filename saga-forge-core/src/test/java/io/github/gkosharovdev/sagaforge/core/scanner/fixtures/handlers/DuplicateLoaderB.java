package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;

/**
 * One of two loaders for {@link ValidAggregate}; together with {@link DuplicateLoaderA} it violates spec
 * FR-014 (model error). On its own it is valid.
 */
public class DuplicateLoaderB {

    @AggregateLoader
    public ValidAggregate load(String id) {
        return null;
    }
}
