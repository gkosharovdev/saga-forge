package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Confirms the order: its stock is held and its payment authorised. Handled by the {@code Order} aggregate. */
@Command
public record ConfirmOrderCommand(@AggregateIdentifier String orderId, String reservationId, String paymentId) {
}
