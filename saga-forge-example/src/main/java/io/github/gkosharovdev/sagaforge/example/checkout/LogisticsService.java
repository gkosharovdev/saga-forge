package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.RequestShipmentCommand;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stands in for the remote Logistics service: it books a shipment and answers nothing now.
 * Logistics reports the parcel's progress hours or days later, as {@code ShipmentDispatchedEvent},
 * {@code ShipmentDeliveredEvent} or {@code DeliveryImpossibleEvent}; the saga waits for them,
 * holding no thread.
 *
 * <p>The booking carries the order's correlation id, read from the saga's process metadata, so that
 * Logistics' support can trace a shipment back to the checkout request that started it.
 */
public class LogisticsService {

  private final Map<String, RequestShipmentCommand> bookings = new LinkedHashMap<>();
  private final Map<String, String> correlationIds = new LinkedHashMap<>();

  /** Books the shipment once per command identity, Logistics' idempotency key. */
  @CommandHandler
  public void handle(RequestShipmentCommand command, CommandId commandId, SagaMetadata metadata) {
    bookings.putIfAbsent(commandId.value(), command);
    metadata
        .get("correlationId")
        .ifPresent(correlationId -> correlationIds.putIfAbsent(commandId.value(), correlationId));
  }

  public Map<String, RequestShipmentCommand> bookings() {
    return Map.copyOf(bookings);
  }

  /** The correlation id each booking was made with, by command identity. */
  public Map<String, String> correlationIds() {
    return Map.copyOf(correlationIds);
  }
}
