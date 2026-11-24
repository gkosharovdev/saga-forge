package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/**
 * Asks the Inventory service to give the order's stock back: it drops a reservation that still holds, books committed
 * items back in once the parcel has returned, and does nothing for an order it holds nothing for. Inventory knows
 * which, so the saga does not have to, and can send it whether or not a reservation was made.
 */
@Command(delivery = CommandDelivery.OUTBOX)
public record ReleaseStockCommand(String orderId) {
}
