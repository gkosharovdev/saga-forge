package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import org.jmolecules.event.types.DomainEvent;

/** The order's stock was reserved. */
public record OrderCompleted(OrderId orderId) implements DomainEvent {}
