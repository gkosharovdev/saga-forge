package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderReadyToShipEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;

/**
 * {@link InboundSagaEventListener} (specs/014-publish-once contracts/inbound-route.md IR1 to IR5).
 */
class InboundSagaEventListenerTest {

  private static final OrderCreatedEvent CREATED = new OrderCreatedEvent("o-1", BigDecimal.ONE);

  private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
  private final InboundSagaEventListener listener =
      new InboundSagaEventListener(
          Set.of(OrderCreatedEvent.class, OrderCompletedEvent.class), dispatcher);

  private void publish(Object payload) {
    listener.onApplicationEvent(new PayloadApplicationEvent<>(this, payload));
  }

  @AfterEach
  void cleanUp() {
    AlreadyPublishedEvents.clear();
  }

  @Test
  @DisplayName(
      "startup: a non-payload event (Spring Data's mapping events) resolves no collaborator")
  void lazyCollaborators() {
    InboundSagaEventListener lazy =
        new InboundSagaEventListener(
            () -> {
              throw new IllegalStateException("runtime resolved too early");
            },
            () -> {
              throw new IllegalStateException("dispatcher resolved too early");
            });

    lazy.onApplicationEvent(new ApplicationEvent(this) {});
    PublishingGuard.run(
        () -> lazy.onApplicationEvent(new PayloadApplicationEvent<>(this, CREATED)));
  }

  @Test
  @DisplayName("IR4: outside a unit of work a saga event is dispatched")
  void dispatchedOutside() {
    publish(CREATED);

    assertThat(dispatcher.dispatched()).containsExactly(CREATED);
  }

  @Test
  @DisplayName("IR2: an event type no saga handles, or a non-payload event, is ignored")
  void othersIgnored() {
    publish(new OrderReadyToShipEvent("o-1"));
    publish("text");

    assertThat(dispatcher.dispatched()).isEmpty();
  }

  @Test
  @DisplayName("IR1: Saga Forge's own publications are ignored")
  void guardedIgnored() {
    PublishingGuard.run(() -> publish(CREATED));

    assertThat(dispatcher.dispatched()).isEmpty();
  }

  @Test
  @DisplayName("IR3: inside a unit of work the event is recorded there and noted as published")
  void recordedInside() {
    OrderCompletedEvent completed = new OrderCompletedEvent("o-1");

    List<Object> recorded = DomainEvents.capture(() -> publish(completed));

    assertThat(recorded).containsExactly(completed);
    assertThat(dispatcher.dispatched()).isEmpty();
    assertThat(AlreadyPublishedEvents.contains(completed)).isTrue();
  }
}
