package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import java.util.List;

/**
 * Records the events an aggregate holds after its command handler returned, through the first
 * {@link AggregateEventSource} that supports it ({@code specs/016-marker-spi-jmolecules}
 * contracts/aggregate-event-source.md). Each event goes to the unit of work's recorder as {@code
 * DomainEvents.record} would send it.
 *
 * <p>Immutable; thread-safe if the sources are.
 */
final class EventHarvest {

  private final List<AggregateEventSource> sources;
  private final DomainTypes domainTypes;

  EventHarvest(List<AggregateEventSource> sources, DomainTypes domainTypes) {
    this.sources = List.copyOf(sources);
    this.domainTypes = domainTypes;
  }

  /**
   * Takes and records the events {@code aggregate} holds; nothing if no source supports it.
   *
   * @throws IllegalArgumentException if the source returns something that is not a domain event
   * @throws IllegalStateException if the source returns {@code null}
   */
  void harvest(Object aggregate) {
    for (AggregateEventSource source : sources) {
      if (source.supports(aggregate.getClass())) {
        record(source, aggregate, source.takeEvents(aggregate));
        return;
      }
    }
  }

  private void record(AggregateEventSource source, Object aggregate, List<Object> events) {
    if (events == null) {
      throw new IllegalStateException(
          "Event source "
              + source.getClass().getName()
              + " returned null for aggregate "
              + aggregate.getClass().getName());
    }
    for (Object event : events) {
      if (event == null || !domainTypes.isDomainEvent(event.getClass())) {
        throw new IllegalArgumentException(
            "Event source "
                + source.getClass().getName()
                + " returned "
                + (event == null ? "null" : event.getClass().getName())
                + " for aggregate "
                + aggregate.getClass().getName()
                + ", which is not a domain event (accepted: "
                + domainTypes.describe(DomainRole.DOMAIN_EVENT)
                + ")");
      }
      DomainEvents.record(event);
    }
  }
}
