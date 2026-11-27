package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import java.math.BigDecimal;
import org.jmolecules.event.types.DomainEvent;

public record OrderCreated(OrderId orderId, BigDecimal amount) implements DomainEvent {}
