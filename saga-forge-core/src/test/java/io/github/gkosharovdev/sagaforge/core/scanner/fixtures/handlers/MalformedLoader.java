package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;

/**
 * Invalid: two {@code @AggregateLoader} methods breaking the loader shape (spec FR-013) — one takes two
 * parameters, the other returns a non-{@code @Aggregate} type.
 */
public class MalformedLoader {

    @AggregateLoader
    public ValidAggregate loadWithTwoParameters(String id, String tenant) {
        return null;
    }

    @AggregateLoader
    public String loadNonAggregate(String id) {
        return id;
    }
}
