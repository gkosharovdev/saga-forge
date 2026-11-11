package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Charges a delivery through {@link DeliveryPayments}. */
@Command
public record ChargeDelivery(String bookingId, long amount) {
}
