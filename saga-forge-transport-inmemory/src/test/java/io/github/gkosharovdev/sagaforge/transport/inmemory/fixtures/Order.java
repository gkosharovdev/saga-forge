package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Marker aggregate of the order module. */
@Aggregate
public final class Order {

    @AggregateIdentifier
    private String orderId;
}
