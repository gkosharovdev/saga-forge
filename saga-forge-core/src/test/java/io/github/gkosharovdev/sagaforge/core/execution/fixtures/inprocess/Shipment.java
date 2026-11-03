package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * Marker aggregate changed by {@link ShipmentService} inside the saga's unit of work. It declares no
 * {@code @CommandHandler}; the service touches it.
 */
@Aggregate
public final class Shipment {

    @AggregateIdentifier
    private final String id;

    public Shipment(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
