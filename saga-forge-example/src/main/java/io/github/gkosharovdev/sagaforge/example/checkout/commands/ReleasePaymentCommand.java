package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/**
 * Gives the customer's money back: Payments voids an authorisation that was never captured and
 * refunds one that was. The Payments context owns that decision because it knows the payment's
 * state; the saga only says that the order will not be delivered.
 */
@Command(delivery = CommandDelivery.OUTBOX)
public record ReleasePaymentCommand(String orderId, String paymentId) {}
