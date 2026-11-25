package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.RequestShipmentCommand;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stands in for the remote Logistics service: it books a shipment and answers nothing now.
 * Logistics reports the parcel's progress hours or days later, as {@code ShipmentDispatchedEvent},
 * {@code ShipmentDeliveredEvent} or {@code DeliveryImpossibleEvent}; the saga waits for them,
 * holding no thread.
 */
public class LogisticsService {

  private final Map<String, RequestShipmentCommand> bookings = new LinkedHashMap<>();

  /** Books the shipment once per command identity, Logistics' idempotency key. */
  @CommandHandler
  public void handle(RequestShipmentCommand command, CommandId commandId) {
    bookings.putIfAbsent(commandId.value(), command);
  }

  public Map<String, RequestShipmentCommand> bookings() {
    return Map.copyOf(bookings);
  }
}
