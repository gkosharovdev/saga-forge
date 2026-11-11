package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/** A logged command (007 FR-010): sent by {@link BillingSaga}, handled after the step commits by {@link CardService}. */
@Command(delivery = CommandDelivery.LOGGED)
public record ChargeCard(@AggregateIdentifier String invoiceId) {
}
