package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Asks the delivery partner, through {@link DeliveryService}, to accept the delivery. */
@Command
public record RequestDelivery(String orderId) {
}
