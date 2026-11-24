package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/** Turns the order's reservation into a stock deduction once the parcel has left the warehouse. */
@Command(delivery = CommandDelivery.OUTBOX)
public record CommitStockCommand(String orderId, String reservationId) {
}
