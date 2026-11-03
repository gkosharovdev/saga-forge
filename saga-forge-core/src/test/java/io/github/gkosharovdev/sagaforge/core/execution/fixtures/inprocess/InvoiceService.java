package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/**
 * Service-hosted handler for {@link IssueInvoice}: changes an {@link Invoice} and records
 * {@link InvoiceIssued}. A plain class with no Saga Forge supertype; {@link Snapshottable} is a test-only
 * fixture contract.
 */
public class InvoiceService implements Snapshottable {

    private final Map<String, Invoice> invoices = new LinkedHashMap<>();
    private final FaultSwitch faults = new FaultSwitch();

    /** The {@link Invoice} id this service uses for an order. */
    public static String invoiceIdFor(String orderId) {
        return "invoice-" + orderId;
    }

    /** Issues the invoice, then throws if a failure is armed, otherwise records {@link InvoiceIssued}. */
    @CommandHandler
    public void handle(IssueInvoice command) {
        String invoiceId = invoiceIdFor(command.orderId());
        invoices.put(invoiceId, new Invoice(invoiceId));
        faults.throwIfArmed();
        DomainEvents.record(new InvoiceIssued(invoiceId, command.orderId(), command.amount()));
    }

    /** Arms a one-shot failure with a default {@link IllegalStateException}. */
    public void failNext() {
        failNext(new IllegalStateException("InvoiceService failure injected"));
    }

    /** Arms a one-shot failure: the next {@code handle} call throws {@code failure} after its change. */
    public void failNext(RuntimeException failure) {
        faults.arm(failure);
    }

    /** Ids of the invoices changed so far, in change order. */
    public Set<String> changedAggregates() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(invoices.keySet()));
    }

    /** Clears all invoices and disarms any pending failure. */
    public void reset() {
        invoices.clear();
        faults.disarm();
    }

    @Override
    public Object snapshot() {
        return new State(List.copyOf(invoices.values()));
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof State state)) {
            throw new IllegalArgumentException("Not an InvoiceService snapshot: " + snapshot);
        }
        invoices.clear();
        state.invoices().forEach(invoice -> invoices.put(invoice.id(), invoice));
    }

    private record State(List<Invoice> invoices) {
    }
}
