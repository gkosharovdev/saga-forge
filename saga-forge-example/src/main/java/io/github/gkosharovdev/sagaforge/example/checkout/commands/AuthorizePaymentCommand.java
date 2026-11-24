package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Asks the payment provider to authorise (hold, not take) the order's amount. Handled in the sending step's unit of
 * work: the provider answers within the call, so the step's {@code @RetryPolicy} covers a provider timeout.
 */
@Command
public record AuthorizePaymentCommand(String orderId, BigDecimal amount) {
}
