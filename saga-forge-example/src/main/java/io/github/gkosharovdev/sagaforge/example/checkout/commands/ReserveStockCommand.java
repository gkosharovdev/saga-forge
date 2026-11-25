package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.example.checkout.OrderLine;
import java.util.List;

/**
 * Asks the Inventory service to hold the order's items. An outbox command: it is stored with the
 * sending step and sent after that step commits, so a slow or failing Inventory call never rolls
 * the checkout back. Inventory answers with {@code StockReservedEvent} or {@code
 * StockUnavailableEvent}.
 */
@Command(delivery = CommandDelivery.OUTBOX)
public record ReserveStockCommand(String orderId, List<OrderLine> lines) {}
