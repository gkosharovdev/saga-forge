package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import org.jmolecules.architecture.cqrs.Command;

@Command
public record ReserveStock(OrderId orderId) {}
