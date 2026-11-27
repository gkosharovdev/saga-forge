package io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;

/** Event of the publish-once fixtures (specs/014-publish-once). */
@DomainEvent(aggregate = Shipment.class)
public record ShipmentInvoiced(@AggregateIdentifier String orderId) {}
