package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate of the billing module. */
@Aggregate
public final class Invoice {

    @AggregateIdentifier
    private String invoiceId;
}
