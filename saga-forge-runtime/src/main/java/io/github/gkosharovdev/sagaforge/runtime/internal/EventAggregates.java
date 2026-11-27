package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes.AggregateResolution;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The aggregate each domain event concerns, resolved once per event type over the registered
 * aggregates (specs/016-marker-spi-jmolecules research R5): the one its link names, else the only
 * aggregate with the link's identifier type. A step records the reference as the aggregate it
 * touched.
 *
 * <p>Thread-safe.
 */
final class EventAggregates {

  private final DomainTypes domainTypes;
  private final List<Class<?>> aggregates;
  private final Map<Class<?>, Class<?>> aggregateByEvent = new ConcurrentHashMap<>();

  private EventAggregates(DomainTypes domainTypes, List<Class<?>> aggregates) {
    this.domainTypes = domainTypes;
    this.aggregates = aggregates;
  }

  /**
   * @param registered the registered classes; their aggregates are the candidates
   * @param domainTypes the vocabularies the model is read with
   */
  static EventAggregates of(Collection<Class<?>> registered, DomainTypes domainTypes) {
    return new EventAggregates(
        domainTypes, registered.stream().filter(domainTypes::isAggregate).toList());
  }

  /**
   * The aggregate {@code domainEvent} concerns, with the identity it carries.
   *
   * @throws IllegalArgumentException if {@code domainEvent} is not a domain event
   * @throws IllegalStateException if its aggregate cannot be resolved or its identity is null,
   *     which a validated model never reaches
   */
  AggregateReference touchedBy(Object domainEvent) {
    Class<?> eventType = domainEvent.getClass();
    if (!domainTypes.isDomainEvent(eventType)) {
      throw new IllegalArgumentException(
          domainTypes.notMarkedMessage(eventType, DomainRole.DOMAIN_EVENT));
    }
    Class<?> aggregate = aggregateByEvent.computeIfAbsent(eventType, this::resolve);
    return AggregateReference.of(aggregate, domainTypes.identifierValue(domainEvent));
  }

  private Class<?> resolve(Class<?> eventType) {
    return switch (domainTypes.aggregateOf(eventType, aggregates)) {
      case AggregateResolution.Resolved resolved -> resolved.aggregate();
      case AggregateResolution.Unresolved unresolved ->
          throw new IllegalStateException(
              domainTypes.displayName(eventType)
                  + " concerns no known aggregate: "
                  + unresolved.reason());
    };
  }
}
