package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderReadyToShipEvent;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * One publication reaches every saga step and every Spring listener once, whatever the route
 * (specs/014-publish-once contracts/inbound-route.md delivery matrix, User Stories 1, 3 and 4),
 * over the runtime's in-memory doubles: no database, so no rollback cases (those are in {@code
 * PublishOnceContainerTest}).
 */
class PublishOnceTest {

  private static final OrderCreatedEvent CREATED = new OrderCreatedEvent("o-1", BigDecimal.ONE);

  private final ApplicationContextRunner runner =
      StarterRunner.withDoubles(
              StarterRunner.runner()
                  .withConfiguration(AutoConfigurations.of(ApplicationEventsConfiguration.class)),
              ShortSaga.class,
              Completer.class)
          .withBean(Completer.class)
          .withBean(Listener.class, Listener::new)
          .withUserConfiguration(OutboxConfig.class);

  private static TestTransport transport(ApplicationContext context) {
    return ((TestTransport) context.getBean(EventDispatcher.class))
        .connect(context.getBean(SagaRuntime.class))
        .loopBack();
  }

  private static String sagaStatus(ApplicationContext context) {
    return context.getBean(StorageProvider.class).findByAssociation("orderId", "o-1").stream()
        .map(instance -> instance.status().name())
        .findFirst()
        .orElse(null);
  }

  private static long dispatchedCount(TestTransport transport, Object event) {
    return transport.dispatched().stream().filter(event::equals).count();
  }

  @Test
  @DisplayName("US1: a saga event published through Spring starts the saga once; listeners once")
  void springPublishReachesSagaAndListener() {
    runner.run(
        context -> {
          TestTransport transport = transport(context);

          context.publishEvent(CREATED);

          assertThat(sagaStatus(context)).isEqualTo("COMPLETED");
          assertThat(dispatchedCount(transport, CREATED)).isEqualTo(1);
          Listener listener = context.getBean(Listener.class);
          assertThat(listener.created).containsExactly(CREATED);
          assertThat(listener.completed).containsExactly(new OrderCompletedEvent("o-1"));
        });
  }

  @Test
  @DisplayName("US1: an event no saga has a step for is not stored")
  void nonSagaEventIgnored() {
    runner.run(
        context -> {
          TestTransport transport = transport(context);

          context.publishEvent(new OrderReadyToShipEvent("o-1"));
          context.publishEvent("not a domain event");

          assertThat(transport.dispatched()).isEmpty();
        });
  }

  @Test
  @DisplayName("US1: the inbound route can be switched off")
  void inboundDisabled() {
    runner
        .withPropertyValues("saga-forge.application-events.inbound.enabled=false")
        .run(
            context -> {
              TestTransport transport = transport(context);
              assertThat(context).doesNotHaveBean(InboundSagaEventListener.class);

              context.publishEvent(CREATED);

              assertThat(transport.dispatched()).isEmpty();
              assertThat(sagaStatus(context)).isNull();
            });
  }

  @Test
  @DisplayName(
      "US3: EventPublisher outside a unit of work reaches listeners once and the saga once")
  void eventPublisherReachesListener() {
    runner.run(
        context -> {
          TestTransport transport = transport(context);
          assertThat(context.getBean(EventPublisher.class))
              .isInstanceOf(ApplicationEventPublishingEventPublisher.class);

          context.getBean(EventPublisher.class).publish(CREATED);

          assertThat(sagaStatus(context)).isEqualTo("COMPLETED");
          assertThat(dispatchedCount(transport, CREATED)).isEqualTo(1);
          assertThat(context.getBean(Listener.class).created).containsExactly(CREATED);
          assertThat(context.getBean(Listener.class).completed).hasSize(1);
        });
  }

  @Test
  @DisplayName("US3: with mode none, EventPublisher reaches sagas only")
  void modeNoneSagasOnly() {
    runner
        .withPropertyValues("saga-forge.application-events.mode=none")
        .run(
            context -> {
              transport(context);
              assertThat(context.getBean(EventPublisher.class))
                  .isNotInstanceOf(ApplicationEventPublishingEventPublisher.class);

              context.getBean(EventPublisher.class).publish(CREATED);

              assertThat(sagaStatus(context)).isEqualTo("COMPLETED");
              assertThat(context.getBean(Listener.class).created).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "US4: Spring-published inside a unit of work: recorded there, continues in process, listeners once")
  void springPublishInsideUnitOfWork() {
    runner.run(
        context -> {
          TestTransport transport = transport(context);
          context.getBean(Completer.class).throughSpring = true;

          context.publishEvent(CREATED);

          assertThat(sagaStatus(context)).isEqualTo("COMPLETED");
          assertThat(transport.reports())
              .singleElement()
              .satisfies(
                  report ->
                      assertThat(report.results().get(0).outcome().name()).isEqualTo("COMPLETED"));
          assertThat(dispatchedCount(transport, new OrderCompletedEvent("o-1"))).isZero();
          assertThat(context.getBean(Listener.class).completed)
              .containsExactly(new OrderCompletedEvent("o-1"));
        });
  }

  @Test
  @DisplayName("US4: events the unit of work publishes to Spring are not taken back in")
  void ownPublicationsNotTakenBack() {
    runner.run(
        context -> {
          TestTransport transport = transport(context);

          context.getBean(EventPublisher.class).publish(CREATED);

          assertThat(transport.dispatched()).containsExactly(CREATED);
        });
  }

  @Test
  @DisplayName("an application-defined EventPublisher is not wrapped")
  void applicationPublisherLeftAlone() {
    EventPublisher own = event -> {};
    runner
        .withBean("myPublisher", EventPublisher.class, () -> own)
        .run(context -> assertThat(context.getBean(EventPublisher.class)).isSameAs(own));
  }

  /** An IN_TRANSACTION outbox over the runtime's transaction boundary. */
  @Configuration(proxyBeanMethods = false)
  static class OutboxConfig {

    @Bean
    Outbox outbox(TransactionBoundary boundary) {
      return CapturingOutbox.inTransaction((InMemoryTransactionBoundary) boundary);
    }
  }

  @Saga
  public static class ShortSaga {

    @SagaId private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void on(OrderCreatedEvent event) {
      commandGateway.send(new Complete(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void done(OrderCompletedEvent event) {
      // ends
    }
  }

  @Command
  public record Complete(String orderId) {}

  /** Records the completion, or publishes it through Spring as a Spring Data save would. */
  public static class Completer {

    private final ApplicationEventPublisher publisher;
    volatile boolean throughSpring;

    public Completer(ApplicationEventPublisher publisher) {
      this.publisher = publisher;
    }

    @CommandHandler
    public void handle(Complete command) {
      OrderCompletedEvent completed = new OrderCompletedEvent(command.orderId());
      if (throughSpring) {
        publisher.publishEvent(completed);
      } else {
        DomainEvents.record(completed);
      }
    }
  }

  public static class Listener {

    final List<Object> created = new CopyOnWriteArrayList<>();
    final List<Object> completed = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(OrderCreatedEvent event) {
      created.add(event);
    }

    @EventListener
    public void on(OrderCompletedEvent event) {
      completed.add(event);
    }
  }
}
