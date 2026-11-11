package io.github.gkosharovdev.sagaforge.engine.fixtures.logged;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Recorded by {@link CardService}; ends {@link BillingSaga}. */
@DomainEvent(aggregate = Invoice.class)
public record CardCharged(@AggregateIdentifier String invoiceId) {
}
