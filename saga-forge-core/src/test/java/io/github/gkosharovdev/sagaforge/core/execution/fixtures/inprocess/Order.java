package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * Marker aggregate named by the externally published {@link OrderPlaced} start event. It is never changed
 * inside the saga's unit of work.
 */
@Aggregate
public final class Order {

    @AggregateIdentifier
    private final String id;

    public Order(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
