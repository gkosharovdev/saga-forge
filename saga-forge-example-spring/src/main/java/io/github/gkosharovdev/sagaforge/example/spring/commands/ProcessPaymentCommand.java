package io.github.gkosharovdev.sagaforge.example.spring.commands;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Asks {@code PaymentService} to take payment for an order. */
@Command
public record ProcessPaymentCommand(String orderId, BigDecimal amount) {
}
