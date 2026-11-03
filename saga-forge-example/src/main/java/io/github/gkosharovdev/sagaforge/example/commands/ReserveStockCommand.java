package io.github.gkosharovdev.sagaforge.example.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/**
 * Asks an {@code Order} to reserve its stock. Handled by an {@code Order} instance method, so
 * {@code orderId} is the {@code @AggregateIdentifier} the engine loads the order by.
 */
@Command
public record ReserveStockCommand(@AggregateIdentifier String orderId) {
}
