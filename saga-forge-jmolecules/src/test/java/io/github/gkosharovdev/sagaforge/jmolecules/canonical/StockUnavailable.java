package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import org.jmolecules.event.types.DomainEvent;

public record StockUnavailable(OrderId orderId) implements DomainEvent {}
