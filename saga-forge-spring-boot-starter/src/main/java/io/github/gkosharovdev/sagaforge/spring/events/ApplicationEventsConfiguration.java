package io.github.gkosharovdev.sagaforge.spring.events;

import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaRuntimeConfiguration;
import io.github.gkosharovdev.sagaforge.spring.transport.ApplicationEventBridge;
import io.github.gkosharovdev.sagaforge.spring.transport.InMemoryTransportConfiguration;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import java.lang.System.Logger.Level;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Saga events as Spring application events, by {@code saga-forge.application-events.mode} ({@code
 * specs/008-spring-boot-starter} FR-018a, contracts/application-events.md): {@code in-transaction}
 * (default) decorates the {@link Outbox} bean with {@link ApplicationEventPublishingOutbox}; {@code
 * after-delivery} registers 007's {@link ApplicationEventBridge}; {@code none} does neither.
 * Exactly one is active, so a listener sees each event once.
 *
 * <p>Since {@code specs/014-publish-once}: {@code in-transaction} also wraps the starter's {@code
 * EventPublisher} in {@link ApplicationEventPublishingEventPublisher}; independently of the mode,
 * {@code saga-forge.application-events.inbound.enabled} (default {@code true}) registers the {@link
 * InboundSagaEventListener}; the deprecated {@code after-delivery} is refused while it is on.
 */
@AutoConfiguration(after = {InMemoryTransportConfiguration.class, SagaRuntimeConfiguration.class})
@ConditionalOnProperty(
    prefix = "saga-forge",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@ConditionalOnClass(SagaRuntime.class)
public class ApplicationEventsConfiguration {

  /**
   * The name of the starter's own {@code EventPublisher} bean ({@code SagaRuntimeConfiguration}).
   */
  static final String SAGA_FORGE_EVENT_PUBLISHER = "sagaForgeEventPublisher";

  /**
   * {@code in-transaction}: every recorded event, published inside the unit of work's transaction.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "saga-forge.application-events",
      name = "mode",
      havingValue = "in-transaction",
      matchIfMissing = true)
  static class InTransaction {

    @Bean
    static BeanPostProcessor sagaForgeApplicationEventPublishingOutbox(
        ObjectProvider<ApplicationEventPublisher> publisher) {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof Outbox outbox
              && !(bean instanceof ApplicationEventPublishingOutbox)) {
            if (outbox.timing() != Outbox.Timing.IN_TRANSACTION) {
              System.getLogger("io.github.gkosharovdev.sagaforge.spring")
                  .log(
                      Level.WARNING,
                      "saga-forge.application-events.mode=in-transaction needs an IN_TRANSACTION outbox;"
                          + " bean '"
                          + beanName
                          + "' is "
                          + outbox.timing()
                          + ", events are not"
                          + " published as application events");
              return bean;
            }
            return new ApplicationEventPublishingOutbox(outbox, publisher.getObject());
          }
          return bean;
        }
      };
    }

    /**
     * Wraps the starter's own {@code EventPublisher} bean (not an application-defined one) so that
     * it publishes to Spring too (014 contracts/inbound-route.md EP1 to EP3).
     */
    @Bean
    static BeanPostProcessor sagaForgeApplicationEventPublishingEventPublisher(
        ObjectProvider<ApplicationEventPublisher> publisher) {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (SAGA_FORGE_EVENT_PUBLISHER.equals(beanName)
              && bean instanceof EventPublisher events
              && !(bean instanceof ApplicationEventPublishingEventPublisher)) {
            return new ApplicationEventPublishingEventPublisher(events, publisher.getObject());
          }
          return bean;
        }
      };
    }
  }

  /**
   * The inbound route (014 contracts/inbound-route.md IR1 to IR5): Spring-published saga events are
   * stored for delivery to sagas.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(
      prefix = "saga-forge.application-events.inbound",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class Inbound {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SagaRuntime.class, EventDispatcher.class})
    InboundSagaEventListener sagaForgeInboundSagaEventListener(
        ObjectProvider<SagaRuntime> runtime, ObjectProvider<EventDispatcher> dispatcher) {
      // Lazy: Spring creates listener beans when the first event is multicast during startup,
      // before
      // the runtime can be built (it verifies the Spring Data repositories being created then).
      return new InboundSagaEventListener(
          () -> runtime.getObject().eventTypes(), dispatcher::getObject);
    }
  }

  /**
   * {@code after-delivery}: 007's bridge, after the transport delivered an event, outside any
   * transaction.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(InMemoryTransport.class)
  @ConditionalOnBean(InMemoryTransport.class)
  @ConditionalOnProperty(
      prefix = "saga-forge.application-events",
      name = "mode",
      havingValue = "after-delivery")
  static class AfterDelivery {

    /**
     * @throws SagaForgeConfigurationException while the inbound route is on (014 research R7,
     *     contracts/starter-configuration.md AD2)
     */
    @Bean
    @ConditionalOnMissingBean
    ApplicationEventBridge sagaForgeApplicationEventBridge(
        InMemoryTransport transport, ApplicationEventPublisher publisher, Environment environment) {
      if (environment.getProperty(
          "saga-forge.application-events.inbound.enabled", Boolean.class, true)) {
        throw new SagaForgeConfigurationException(
            "saga-forge.application-events.mode=after-delivery re-publishes delivered events, which"
                + " the inbound route (saga-forge.application-events.inbound.enabled=true) already"
                + " received from Spring.",
            "Use saga-forge.application-events.mode=in-transaction, or set"
                + " saga-forge.application-events.inbound.enabled=false.");
      }
      System.getLogger("io.github.gkosharovdev.sagaforge.spring")
          .log(
              Level.WARNING,
              "saga.applicationEvents.afterDeliveryDeprecated"
                  + " saga-forge.application-events.mode=after-delivery is deprecated;"
                  + " use in-transaction");
      return new ApplicationEventBridge(transport, publisher);
    }
  }
}
