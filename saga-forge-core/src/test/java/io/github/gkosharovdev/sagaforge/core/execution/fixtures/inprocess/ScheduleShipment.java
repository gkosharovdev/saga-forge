package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Sent by {@link ThreeStepSaga#onInvoiceIssued(InvoiceIssued)}; handled by {@link ShipmentService}.
 *
 * @param orderId   the saga's correlation value
 * @param invoiceId the invoice to ship against
 */
@Command
public record ScheduleShipment(String orderId, String invoiceId) {
}
