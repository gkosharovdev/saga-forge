package io.github.gkosharovdev.sagaforge.spring.fixtures.harvest;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.ShipmentModel.PackShipment;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.ShipmentModel.ShipmentPacked;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

/**
 * A Spring Data aggregate that holds its events for {@code @DomainEvents}; it never calls Saga
 * Forge's {@code DomainEvents.record} ({@code specs/016-marker-spi-jmolecules} spec US2 scenarios 4
 * and 5). {@code @KeySpace} assigns it to the map repository module, beside Spring Data JDBC on the
 * test class path.
 */
@Aggregate
public class Shipment {

  @Id @AggregateIdentifier private String shipmentId;

  @Transient private final List<Object> events = new ArrayList<>();

  public Shipment(String shipmentId) {
    this.shipmentId = shipmentId;
  }

  @CommandHandler
  public void handle(PackShipment command) {
    events.add(new ShipmentPacked(shipmentId));
  }

  public String shipmentId() {
    return shipmentId;
  }

  @DomainEvents
  public List<Object> domainEvents() {
    return List.copyOf(events);
  }

  @AfterDomainEventPublication
  public void clearDomainEvents() {
    events.clear();
  }
}
