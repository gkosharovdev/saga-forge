package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Labels an existing {@link Shipment} (aggregate instance handler). */
@Command
public record LabelShipment(@AggregateIdentifier String shipmentId) {
}
