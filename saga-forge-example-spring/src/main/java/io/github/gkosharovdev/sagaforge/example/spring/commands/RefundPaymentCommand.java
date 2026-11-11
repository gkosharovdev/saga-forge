package io.github.gkosharovdev.sagaforge.example.spring.commands;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Asks {@code PaymentService} to refund a payment; sent by the saga's compensation. */
@Command
public record RefundPaymentCommand(String orderId, String paymentId, BigDecimal amount) {
}
