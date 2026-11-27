package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaForgeDomainTypesConfiguration;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.types.Identifier;
import org.jmolecules.event.types.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A jMolecules event published through Spring reaches its saga once, through 014's inbound route
 * unchanged (specs/016-marker-spi-jmolecules FR-036).
 */
class JMoleculesInboundRouteTest {

  public record ParcelId(String value) implements Identifier {
    @Override
    public String toString() {
      return value;
    }
  }

  @AggregateRoot
  public static class Parcel {
    @Identity ParcelId id;
  }

  public record ParcelRegistered(ParcelId parcel) implements DomainEvent {}

  @Saga
  public static class RegistrationSaga {

    @SagaId private String sagaId;

    @StartSaga
    @EndSaga
    @SagaStep(associationProperty = "parcel")
    public void on(ParcelRegistered event) {
      // starts and ends
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OutboxConfig {

    @Bean
    Outbox outbox(TransactionBoundary boundary) {
      return CapturingOutbox.inTransaction((InMemoryTransactionBoundary) boundary);
    }
  }

  private final ApplicationContextRunner runner =
      StarterRunner.withDoubles(
              StarterRunner.runner()
                  .withConfiguration(
                      AutoConfigurations.of(
                          SagaForgeDomainTypesConfiguration.class,
                          ApplicationEventsConfiguration.class)),
              RegistrationSaga.class,
              Parcel.class)
          .withUserConfiguration(OutboxConfig.class);

  @Test
  @DisplayName("a jMolecules event published through Spring starts its saga once")
  void publishedThroughSpring() {
    runner.run(
        context -> {
          SagaRuntime runtime = context.getBean(SagaRuntime.class);
          TestTransport transport =
              ((TestTransport) context.getBean(EventDispatcher.class)).connect(runtime).loopBack();
          ParcelRegistered registered = new ParcelRegistered(new ParcelId("p-1"));
          assertThat(runtime.eventTypes()).contains(ParcelRegistered.class);

          context.publishEvent(registered);

          assertThat(transport.dispatched().stream().filter(registered::equals)).hasSize(1);
          assertThat(context.getBean(StorageProvider.class).findByAssociation("parcel", "p-1"))
              .singleElement()
              .extracting(instance -> instance.status().name())
              .isEqualTo("COMPLETED");
        });
  }
}
