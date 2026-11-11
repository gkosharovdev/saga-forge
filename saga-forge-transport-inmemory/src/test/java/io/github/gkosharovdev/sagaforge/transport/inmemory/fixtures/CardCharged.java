package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link CardService} handling the logged {@link ChargeCard}; ends {@link BillingSaga}. */
@DomainEvent(aggregate = Invoice.class)
public record CardCharged(@AggregateIdentifier String invoiceId) {
}
