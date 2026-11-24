package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;

/** Takes the authorised amount once the parcel has been dispatched. */
@Command(delivery = CommandDelivery.OUTBOX)
public record CapturePaymentCommand(String orderId, String paymentId) {
}
