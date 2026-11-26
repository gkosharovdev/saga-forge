package io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/** Aggregate of the publish-once fixtures (specs/014-publish-once). */
@Aggregate
public final class Shipment {

  @AggregateIdentifier private String orderId;
}
