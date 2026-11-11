package io.github.gkosharovdev.sagaforge.runtime.fixtures.outbox;


import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/** A outbox command (007 FR-010). */
@Command(delivery = CommandDelivery.OUTBOX)
public record ChargeCard(String invoiceId) {
}
