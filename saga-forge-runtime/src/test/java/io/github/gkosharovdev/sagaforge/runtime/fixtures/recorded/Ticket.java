package io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded;


import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate of the recorded-events fixture (008). */
@Aggregate
public final class Ticket {

    @AggregateIdentifier
    private String ticketId;
}
