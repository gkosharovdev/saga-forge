package io.github.gkosharovdev.sagaforge.spring.actuator;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.metadata.SagaForgeMetadataConfiguration;
import io.github.gkosharovdev.sagaforge.spring.storage.SchemaInitializer;
import io.github.gkosharovdev.sagaforge.spring.transport.InMemoryTransportConfiguration;
import io.github.gkosharovdev.sagaforge.spring.transport.TransportProperties;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

/**
 * The {@code sagaforge} Actuator endpoint and health indicator over the in-memory transport's
 * message store ({@code specs/008-spring-boot-starter} FR-020), with the relay's leadership ({@code
 * specs/013-active-standby-relay} FR-022, FR-023). Registered only when Actuator is on the class
 * path; switched by Spring Boot's {@code management.endpoint.sagaforge.*}, {@code
 * management.endpoints.web.exposure.*} and {@code management.health.sagaforge.enabled}.
 */
@AutoConfiguration(after = InMemoryTransportConfiguration.class)
@ConditionalOnProperty(
    prefix = "saga-forge",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@ConditionalOnClass(
    name = {
      "org.springframework.boot.actuate.endpoint.annotation.Endpoint",
      "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint",
      "io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport"
    })
@ConditionalOnBean(InMemoryTransport.class)
public class SagaForgeActuatorConfiguration {

  private static final String POSTGRES_STORAGE =
      "io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider";

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnAvailableEndpoint(endpoint = SagaForgeEndpoint.class)
  public SagaForgeEndpoint sagaForgeEndpoint(
      InMemoryTransport transport,
      ObjectProvider<DomainTypes> domainTypes,
      ObjectProvider<AggregateEventSource> eventSources,
      ObjectProvider<MetadataAccessor> metadataAccessor,
      BeanFactory beanFactory) {
    return new SagaForgeEndpoint(
        transport.queries(),
        transport::leadership,
        domainTypes.getIfAvailable(DomainTypes::builtIn),
        eventSources.orderedStream().toList(),
        () -> schema(beanFactory),
        SagaForgeMetadataConfiguration.describe(metadataAccessor.getIfAvailable()));
  }

  /**
   * The schema initializer's status, when there is one; the Postgres module, which its types come
   * from, is optional, so they are touched only once it is known to be present.
   */
  private static Map<String, Object> schema(BeanFactory beanFactory) {
    if (!ClassUtils.isPresent(
        POSTGRES_STORAGE, SagaForgeActuatorConfiguration.class.getClassLoader())) {
      return null;
    }
    SchemaInitializer initializer =
        beanFactory.getBeanProvider(SchemaInitializer.class).getIfAvailable();
    return initializer == null ? null : initializer.status().view();
  }

  /** The health indicator, when Spring Boot's health module is present. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.springframework.boot.health.contributor.AbstractHealthIndicator",
        "org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator"
      })
  static class Health {

    /** Named {@code sagaforgeHealthIndicator}, so the contributor is {@code sagaforge}. */
    @Bean
    @ConditionalOnMissingBean(name = "sagaforgeHealthIndicator")
    @ConditionalOnEnabledHealthIndicator("sagaforge")
    public SagaForgeHealthIndicator sagaforgeHealthIndicator(
        InMemoryTransport transport,
        ObjectProvider<TransportProperties> properties,
        ObjectProvider<Clock> clock,
        ObjectProvider<SagaRuntime> runtime) {
      Duration leaseDuration =
          properties.stream()
              .findFirst()
              .map(p -> p.getLeadership().getLeaseDuration())
              .orElse(LeadershipSettings.defaults().leaseDuration());
      return new SagaForgeHealthIndicator(
          transport.queries(),
          transport::leadership,
          leaseDuration,
          clock.getIfUnique(Clock::systemUTC),
          () ->
              Optional.ofNullable(runtime.getIfUnique())
                  .filter(SagaRuntime::isStarted)
                  .map(started -> started.operations().count(Set.of())));
    }
  }
}
