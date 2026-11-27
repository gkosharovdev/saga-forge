package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import org.jmolecules.event.types.DomainEvent;

/** The order was paid for, but its stock cannot be reserved. */
public record StockUnavailable(OrderId orderId) implements DomainEvent {}
