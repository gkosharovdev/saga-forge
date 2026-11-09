package io.github.gkosharovdev.sagaforge.example.commands;

import java.math.BigDecimal;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Asks {@code PaymentService} to refund a payment; sent by {@code OrderProcessingSaga.compensatePayment}. Handled by
 * a service, so it needs no {@code @AggregateIdentifier}.
 */
@Command
public record RefundPaymentCommand(String orderId, String paymentId, BigDecimal amount) {
}
