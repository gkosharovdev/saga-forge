package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Sent by {@link ThreeStepSaga#onItemsReserved(ItemsReserved)}; handled by {@link InvoiceService}.
 *
 * @param orderId the saga's correlation value
 * @param amount  the amount to invoice, read from {@code SagaContext}
 */
@Command
public record IssueInvoice(String orderId, long amount) {
}
