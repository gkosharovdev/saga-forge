package io.github.gkosharovdev.sagaforge.engine.fixtures.logged;


import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/** A logged command (007 FR-010). */
@Command(delivery = CommandDelivery.LOGGED)
public record ChargeCard(String invoiceId) {
}
