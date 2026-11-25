package io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import java.time.Instant;

/** The events of {@link ShipmentSaga}. */
public final class ShipmentEvents {

  private ShipmentEvents() {}

  /**
   * Starts the saga; schedules a {@link ShipmentCommands.Ship} at {@code at}, and cancels it when
   * {@code cancelAtOnce}.
   */
  @DomainEvent(aggregate = Shipment.class)
  public record Booked(@AggregateIdentifier String shipmentId, Instant at, boolean cancelAtOnce) {}

  /** Cancels the scheduled ship command. */
  @DomainEvent(aggregate = Shipment.class)
  public record Withdrawn(@AggregateIdentifier String shipmentId) {}

  /** Tries to schedule a command that is not an outbox command. */
  @DomainEvent(aggregate = Shipment.class)
  public record Misused(@AggregateIdentifier String shipmentId) {}

  /** Ends the saga. */
  @DomainEvent(aggregate = Shipment.class)
  public record Shipped(@AggregateIdentifier String shipmentId) {}
}
