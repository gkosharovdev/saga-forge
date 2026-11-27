package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import java.math.BigDecimal;
import org.jmolecules.event.types.DomainEvent;

/** An order was paid for; {@code paymentId} is the payment's reference, not a link. */
public record PaymentProcessed(OrderId orderId, String paymentId, BigDecimal amount)
    implements DomainEvent {}
