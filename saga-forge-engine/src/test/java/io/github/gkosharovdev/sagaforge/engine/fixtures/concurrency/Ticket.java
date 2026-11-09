package io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate every concurrency-fixture event names. */
@Aggregate
public final class Ticket {

    @AggregateIdentifier
    private String ticketId;
}
