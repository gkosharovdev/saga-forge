package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Refunds a payment through {@link PaymentService}; sent by the compensation. */
@Command
public record Refund(String orderId, String paymentId, long amount) {
}
