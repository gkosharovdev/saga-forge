package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.Shipment;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.ShipmentModel.ShipmentEvents;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.ShipmentModel.ShipmentPacked;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.ShipmentModel.ShipmentRequested;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.ShipmentModel.ShippingSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.harvest.ShipmentRepository;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.map.repository.config.EnableMapRepositories;

/**
 * A Spring Data aggregate with {@code @DomainEvents}, saved by the starter's {@code
 * SpringDataAggregateResolver} inside a command handler ({@code specs/016-marker-spi-jmolecules}
 * FR-024, spec US2 scenarios 4 and 5): with an event source the events are harvested, recorded once
 * and published to Spring once by the unit of work; without one, Spring Data publishes them on save
 * and 014's inbound route records them in the unit of work. Either way the saga step and a Spring
 * listener each see the event once. Over a map repository and the runtime's in-memory doubles; the
 * Modulith interop variant is not covered (G-016-07).
 */
class HarvestWithSpringDataTest {

  private static final String SHIPMENT = "s-1";
  private static final ShipmentPacked PACKED = new ShipmentPacked(SHIPMENT);

  private final ApplicationContextRunner runner =
      StarterRunner.withDoubles(
              StarterRunner.runner()
                  .withConfiguration(AutoConfigurations.of(ApplicationEventsConfiguration.class)),
              ShippingSaga.class,
              Shipment.class)
          .withUserConfiguration(Repositories.class)
          .withBean(Listener.class, Listener::new);

  @Configuration(proxyBeanMethods = false)
  @EnableMapRepositories(
      basePackageClasses = ShipmentRepository.class,
      considerNestedRepositories = true)
  static class Repositories {

    @Bean
    Outbox outbox(TransactionBoundary boundary) {
      return CapturingOutbox.inTransaction((InMemoryTransactionBoundary) boundary);
    }
  }

  /** A Spring listener for the packed event. */
  static final class Listener {

    final List<Object> packed = new CopyOnWriteArrayList<>();

    @EventListener
    void on(ShipmentPacked event) {
      packed.add(event);
    }
  }

  private static TestTransport transport(ApplicationContext context) {
    return ((TestTransport) context.getBean(EventDispatcher.class))
        .connect(context.getBean(SagaRuntime.class))
        .loopBack();
  }

  private static SagaInstance instance(ApplicationContext context) {
    return context.getBean(StorageProvider.class).findByAssociation("shipmentId", SHIPMENT).get(0);
  }

  private static void run(ApplicationContext context) {
    TestTransport transport = transport(context);
    context.getBean(ShipmentRepository.class).save(new Shipment(SHIPMENT));
    context.getBean(Listener.class).packed.clear();

    context.getBean(SagaRuntime.class).receive(new ShipmentRequested(SHIPMENT));

    SagaInstance instance = instance(context);
    assertThat(instance.status().name()).isEqualTo("COMPLETED");
    assertThat(instance.steps().get("onPacked").status()).isEqualTo(StepStatus.COMPLETED);
    assertThat(instance.steps().get("onPacked").attempts()).isEqualTo(1);
    assertThat(transport.dispatched()).doesNotContain(PACKED);
    assertThat(context.getBean(Listener.class).packed).containsExactly(PACKED);
    assertThat(context.getBean(ShipmentRepository.class).findById(SHIPMENT))
        .hasValueSatisfying(saved -> assertThat(saved.domainEvents()).isEmpty());
  }

  @Test
  @DisplayName("scenario 4: with an event source the saga and the listener each see it once")
  void harvested() {
    runner
        .withBean(ShipmentEvents.class, ShipmentEvents::new)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              run(context);
              assertThat(context.getBean(ShipmentEvents.class).taken()).isEqualTo(1);
            });
  }

  @Test
  @DisplayName("scenario 5: without one, Spring Data's publication reaches the saga as in 014")
  void publishedBySpringData() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          run(context);
        });
  }
}
