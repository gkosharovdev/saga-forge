package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.example.checkout.Order;

/**
 * Rejects the order with a reason the customer is shown. Handled by the {@code Order} aggregate.
 */
@Command
public record RejectOrderCommand(
    @AggregateIdentifier String orderId, Order.RejectionReason reason) {}
