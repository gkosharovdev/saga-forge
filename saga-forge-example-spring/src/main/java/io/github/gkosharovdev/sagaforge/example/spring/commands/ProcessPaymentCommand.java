package io.github.gkosharovdev.sagaforge.example.spring.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import java.math.BigDecimal;

/** Asks {@code PaymentService} to take payment for an order. */
@Command
public record ProcessPaymentCommand(String orderId, BigDecimal amount) {}
