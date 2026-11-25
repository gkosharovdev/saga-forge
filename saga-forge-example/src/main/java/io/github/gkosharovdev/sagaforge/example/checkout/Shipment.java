package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * A shipment run by the Logistics service. Its state lives in that other service; the class only
 * names the aggregate its events belong to.
 */
@Aggregate
public class Shipment {

  @AggregateIdentifier private String shipmentId;
}
