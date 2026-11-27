package io.github.gkosharovdev.sagaforge.spring.runtime;

import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.host.SpringDataAggregateResolver;
import io.github.gkosharovdev.sagaforge.spring.host.SpringHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.spring.host.SpringSagaObjectProvider;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The checks of {@code specs/008-spring-boot-starter} FR-010 to FR-013 that the runtime's own
 * startup checks cannot see: each saga has a Spring way to be created, each aggregate's identifier
 * fits its Spring Data repository, each handler host is a bean. Aggregates are recognised in every
 * vocabulary of the {@link DomainTypes}.
 */
final class ModelHostChecks {

  private final DomainTypes domainTypes;
  private final SagaModelCollector collector;
  private final SagaObjectProvider sagaObjects;
  private final HandlerInstanceProvider handlerInstances;
  private final ObjectProvider<AggregateResolver> aggregateResolvers;

  ModelHostChecks(
      DomainTypes domainTypes,
      SagaModelCollector collector,
      SagaObjectProvider sagaObjects,
      HandlerInstanceProvider handlerInstances,
      ObjectProvider<AggregateResolver> aggregateResolvers) {
    this.domainTypes = domainTypes;
    this.collector = collector;
    this.sagaObjects = sagaObjects;
    this.handlerInstances = handlerInstances;
    this.aggregateResolvers = aggregateResolvers;
  }

  /**
   * @throws SagaForgeConfigurationException on the first class the host cannot serve
   */
  void verify(List<Class<?>> model) {
    for (Class<?> type : model) {
      if (type.isAnnotationPresent(Saga.class)) {
        if (sagaObjects instanceof SpringSagaObjectProvider spring) {
          spring.verify(type);
        }
      } else if (domainTypes.isAggregate(type)) {
        aggregateResolvers
            .orderedStream()
            .filter(SpringDataAggregateResolver.class::isInstance)
            .map(SpringDataAggregateResolver.class::cast)
            .forEach(resolver -> resolver.verify(type));
      } else if (collector.isModelClass(type)
          && handlerInstances instanceof SpringHandlerInstanceProvider spring) {
        spring.verify(type);
      }
    }
  }
}
