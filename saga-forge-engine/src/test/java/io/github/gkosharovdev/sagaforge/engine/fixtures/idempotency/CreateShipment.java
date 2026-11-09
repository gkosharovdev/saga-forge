package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Creates a {@link Shipment} (creation handler). */
@Command
public record CreateShipment(String shipmentId, String orderId) {
}
