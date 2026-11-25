package io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** The aggregate the shipment events belong to. */
@Aggregate
public final class Shipment {

  @AggregateIdentifier private String shipmentId;
}
