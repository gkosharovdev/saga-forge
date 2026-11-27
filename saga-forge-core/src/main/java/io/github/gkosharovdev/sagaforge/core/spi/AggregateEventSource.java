package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;

/**
 * Takes the domain events an aggregate holds, so the runtime records them as if the command handler
 * had called {@code DomainEvents.record} ({@code specs/016-marker-spi-jmolecules} FR-020 to
 * FR-026). Implemented by applications or integrations for the way their aggregates keep pending
 * events; never by the domain itself.
 *
 * <p>The runtime asks the first registered source that supports the aggregate's class, after an
 * aggregate's command handler returned (an instance method, a constructor or a static factory) and
 * before the aggregate is saved. Service handlers are not harvested. The taken events are recorded
 * after any event the handler recorded itself, in the order returned; they continue the saga in
 * process, go to the outbox and roll back with the unit of work exactly like recorded events.
 *
 * <p>Implementations are thread-safe; the aggregate instance is used by one thread at a time.
 */
public interface AggregateEventSource {

  /**
   * Whether this source takes events from aggregates of {@code aggregateType}.
   *
   * @param aggregateType the aggregate's class
   * @return {@code true} if {@link #takeEvents} applies to its instances
   * @throws IllegalArgumentException if {@code aggregateType} is {@code null}
   */
  boolean supports(Class<?> aggregateType);

  /**
   * The events {@code aggregate} holds, in the order it registered them, and clears them: a later
   * call returns only events registered after this one.
   *
   * @param aggregate an aggregate of a supported type
   * @return the events; never {@code null}, empty when there are none
   * @throws IllegalArgumentException if {@code aggregate} is {@code null}
   */
  List<Object> takeEvents(Object aggregate);
}
