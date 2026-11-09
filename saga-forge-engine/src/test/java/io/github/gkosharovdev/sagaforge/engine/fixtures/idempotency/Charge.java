package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Charges {@code amount} for an order through {@link ChargeService}. */
@Command
public record Charge(String orderId, long amount) {
}
