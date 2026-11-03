package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/**
 * Recorded by {@link ShipmentService} after it scheduled a shipment. Triggers the {@code @EndSaga} step of
 * {@link ThreeStepSaga}.
 *
 * @param shipmentId the {@link Shipment} that changed
 * @param orderId    the saga's correlation value
 * @param invoiceId  the invoice the shipment was scheduled for
 */
@DomainEvent(aggregate = Shipment.class)
public record ShipmentScheduled(@AggregateIdentifier String shipmentId, String orderId, String invoiceId) {
}
