package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import org.jmolecules.event.annotation.DomainEvent;

/** Marked by annotation, where the other events implement jMolecules' type. */
@DomainEvent
public record OrderCompleted(OrderId orderId) {}
