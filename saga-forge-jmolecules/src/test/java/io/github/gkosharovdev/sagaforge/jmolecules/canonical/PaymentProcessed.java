package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import java.math.BigDecimal;
import org.jmolecules.event.types.DomainEvent;

/** The payment reference is a plain value, not a link (only {@code orderId} is an Identifier). */
public record PaymentProcessed(OrderId orderId, String paymentId, BigDecimal amount)
    implements DomainEvent {}
