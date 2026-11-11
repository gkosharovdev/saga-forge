package io.github.gkosharovdev.sagaforge.engine.fixtures.logged;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Starts {@link BillingSaga}. {@code failAfterSend} makes the start step throw after sending; {@code transientFailures}
 * makes it throw a transient failure that many times first.
 */
@DomainEvent(aggregate = Invoice.class)
public record InvoiceIssued(@AggregateIdentifier String invoiceId, boolean failAfterSend, int transientFailures) {

    public InvoiceIssued(String invoiceId) {
        this(invoiceId, false, 0);
    }
}
