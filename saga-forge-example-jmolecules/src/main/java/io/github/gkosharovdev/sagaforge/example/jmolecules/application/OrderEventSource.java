package io.github.gkosharovdev.sagaforge.example.jmolecules.application;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Order;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Takes the events an {@link Order} holds after its command handler, so they continue the saga like
 * recorded events ({@code specs/016-marker-spi-jmolecules} FR-020). The domain keeps its own
 * convention, {@code domainEvents()} and {@code clearDomainEvents()}, and never depends on Saga
 * Forge.
 */
@Component
public class OrderEventSource implements AggregateEventSource {

  @Override
  public boolean supports(Class<?> aggregateType) {
    return Order.class.isAssignableFrom(aggregateType);
  }

  @Override
  public List<Object> takeEvents(Object aggregate) {
    Order order = (Order) aggregate;
    List<Object> events = order.domainEvents();
    order.clearDomainEvents();
    return events;
  }
}
