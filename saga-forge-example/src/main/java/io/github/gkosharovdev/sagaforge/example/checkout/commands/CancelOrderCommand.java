package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Cancels a confirmed order that cannot be delivered. Handled by the {@code Order} aggregate. */
@Command
public record CancelOrderCommand(@AggregateIdentifier String orderId, String reason) {}
