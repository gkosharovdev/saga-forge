package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Notifies the customer of an order through {@link NotifyService}. */
@Command
public record Notify(String orderId) {
}
