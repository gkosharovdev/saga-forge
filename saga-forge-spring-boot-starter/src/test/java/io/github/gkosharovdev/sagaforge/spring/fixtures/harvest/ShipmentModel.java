package io.github.gkosharovdev.sagaforge.spring.fixtures.harvest;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The saga, events, command, repository and event source around {@link Shipment}: {@code
 * ShippingSaga} starts on {@link ShipmentRequested}, packs the shipment and ends on {@link
 * ShipmentPacked}, all on {@code associationProperty = "shipmentId"}.
 */
public final class ShipmentModel {

  private ShipmentModel() {}

  @DomainEvent(aggregate = Shipment.class)
  public record ShipmentRequested(@AggregateIdentifier String shipmentId) {}

  @DomainEvent(aggregate = Shipment.class)
  public record ShipmentPacked(@AggregateIdentifier String shipmentId) {}

  @Command
  public record PackShipment(@AggregateIdentifier String shipmentId) {}

  @Saga
  public static final class ShippingSaga {

    @SagaId private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "shipmentId")
    public void onRequested(ShipmentRequested event) {
      commandGateway.send(new PackShipment(event.shipmentId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "shipmentId")
    public void onPacked(ShipmentPacked event) {
      // Outcome step.
    }
  }

  /**
   * Takes a {@link Shipment}'s events the way Spring Data would, so Spring Data publishes nothing
   * when the shipment is saved.
   */
  public static final class ShipmentEvents implements AggregateEventSource {

    private final AtomicInteger taken = new AtomicInteger();

    @Override
    public boolean supports(Class<?> aggregateType) {
      return Shipment.class.isAssignableFrom(aggregateType);
    }

    @Override
    public List<Object> takeEvents(Object aggregate) {
      Shipment shipment = (Shipment) aggregate;
      List<Object> events = shipment.domainEvents();
      shipment.clearDomainEvents();
      taken.addAndGet(events.size());
      return events;
    }

    /** How many events this source took. */
    public int taken() {
      return taken.get();
    }
  }
}
