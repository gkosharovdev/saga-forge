package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import java.math.BigDecimal;
import org.jmolecules.event.types.DomainEvent;

/** An order was placed; starts the order saga. */
public record OrderCreated(OrderId orderId, BigDecimal amount) implements DomainEvent {}
