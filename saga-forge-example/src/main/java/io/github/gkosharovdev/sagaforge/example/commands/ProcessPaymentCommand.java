package io.github.gkosharovdev.sagaforge.example.commands;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Asks {@code PaymentService} to take payment for an order. Handled by a service, so it needs no
 * {@code @AggregateIdentifier}.
 */
@Command
public record ProcessPaymentCommand(String orderId, BigDecimal amount) {
}
