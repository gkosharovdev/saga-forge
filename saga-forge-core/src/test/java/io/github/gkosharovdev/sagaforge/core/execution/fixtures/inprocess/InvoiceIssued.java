package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Recorded by {@link InvoiceService} after it issued an invoice.
 *
 * @param invoiceId the {@link Invoice} that changed
 * @param orderId   the saga's correlation value
 * @param amount    the invoiced amount, which the saga read from {@code SagaContext}
 */
@DomainEvent(aggregate = Invoice.class)
public record InvoiceIssued(@AggregateIdentifier String invoiceId, String orderId, long amount) {
}
