package io.github.gkosharovdev.sagaforge.example.spring.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Asks an {@code Order} to reserve its stock; {@code orderId} is the identifier it is loaded by. */
@Command
public record ReserveStockCommand(@AggregateIdentifier String orderId) {
}
