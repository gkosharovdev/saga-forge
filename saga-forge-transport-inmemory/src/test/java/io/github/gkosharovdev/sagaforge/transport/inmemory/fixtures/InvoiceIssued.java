package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Starts {@link BillingSaga}. */
@DomainEvent(aggregate = Invoice.class)
public record InvoiceIssued(@AggregateIdentifier String invoiceId) {
}
