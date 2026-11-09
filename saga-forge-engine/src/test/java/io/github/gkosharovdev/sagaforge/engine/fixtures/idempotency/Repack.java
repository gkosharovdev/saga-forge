package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Makes {@link Repacker} record {@link OrderPacked} twice for the order. */
@Command
public record Repack(String orderId) {
}
