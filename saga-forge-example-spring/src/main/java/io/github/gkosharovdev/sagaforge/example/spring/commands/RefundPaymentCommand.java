package io.github.gkosharovdev.sagaforge.example.spring.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import java.math.BigDecimal;

/** Asks {@code PaymentService} to refund a payment; sent by the saga's compensation. */
@Command
public record RefundPaymentCommand(String orderId, String paymentId, BigDecimal amount) {}
