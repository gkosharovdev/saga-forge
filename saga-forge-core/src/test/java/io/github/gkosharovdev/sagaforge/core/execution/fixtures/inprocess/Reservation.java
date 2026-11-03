package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * Marker aggregate changed by {@link ReservationService} inside the saga's unit of work. It declares no
 * {@code @CommandHandler}; the service touches it.
 */
@Aggregate
public final class Reservation {

    @AggregateIdentifier
    private final String id;

    public Reservation(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
