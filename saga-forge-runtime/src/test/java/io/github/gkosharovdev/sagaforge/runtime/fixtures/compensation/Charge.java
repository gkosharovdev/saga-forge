package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Takes the order's payment through {@link PaymentService}. */
@Command
public record Charge(String orderId, long amount) {
}
