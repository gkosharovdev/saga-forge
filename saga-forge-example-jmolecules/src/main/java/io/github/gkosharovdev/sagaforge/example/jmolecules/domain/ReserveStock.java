package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import org.jmolecules.architecture.cqrs.Command;

/** Reserve the order's stock; handled by the {@link Order} it names. */
@Command
public record ReserveStock(OrderId orderId) {}
