package io.github.gkosharovdev.sagaforge.example.checkout.commands;

import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.example.checkout.OrderLine;

/**
 * Asks the Logistics service to pick, pack and deliver the order. Logistics answers over the following hours or days
 * with {@code ShipmentDispatchedEvent}, then {@code ShipmentDeliveredEvent} or {@code DeliveryImpossibleEvent}.
 */
@Command(delivery = CommandDelivery.OUTBOX)
public record RequestShipmentCommand(String orderId, List<OrderLine> lines, String deliveryAddress) {
}
