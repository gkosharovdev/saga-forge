package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import java.math.BigDecimal;
import org.jmolecules.architecture.cqrs.Command;

/**
 * Refund a payment. {@code refundKey} is stable across retries (the saga passes its own id), so a
 * refund that runs again refunds once.
 */
@Command
public record RefundPayment(String refundKey, String paymentId, BigDecimal amount) {}
