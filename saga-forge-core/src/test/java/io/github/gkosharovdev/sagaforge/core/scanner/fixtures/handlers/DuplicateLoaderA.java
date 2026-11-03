package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;

/**
 * One of two loaders for {@link ValidAggregate}; together with {@link DuplicateLoaderB} it violates spec
 * FR-014 (model error). On its own it is valid.
 */
public class DuplicateLoaderA {

    @AggregateLoader
    public ValidAggregate load(String id) {
        return null;
    }
}
