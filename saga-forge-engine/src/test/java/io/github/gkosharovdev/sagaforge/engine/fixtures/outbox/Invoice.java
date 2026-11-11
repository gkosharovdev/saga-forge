package io.github.gkosharovdev.sagaforge.engine.fixtures.outbox;


import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate of the outbox-command fixture (specs/007-in-memory-transport). */
@Aggregate
public final class Invoice {

    @AggregateIdentifier
    private String invoiceId;
}
