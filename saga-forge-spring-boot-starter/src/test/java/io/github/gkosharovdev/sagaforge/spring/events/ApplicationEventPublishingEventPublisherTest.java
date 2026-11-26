package io.github.gkosharovdev.sagaforge.spring.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link ApplicationEventPublishingEventPublisher} (specs/014-publish-once
 * contracts/inbound-route.md EP1 to EP3).
 */
class ApplicationEventPublishingEventPublisherTest {

  private final List<String> calls = new ArrayList<>();
  private final List<Boolean> guarded = new ArrayList<>();
  private final ApplicationEventPublisher spring =
      new ApplicationEventPublisher() {
        @Override
        public void publishEvent(Object event) {
          calls.add("spring " + event);
          guarded.add(PublishingGuard.active());
        }
      };

  @Test
  @DisplayName("EP2: outside a unit of work, delegate first, then Spring under the guard")
  void outside() {
    EventPublisher delegate = event -> calls.add("delegate " + event);

    new ApplicationEventPublishingEventPublisher(delegate, spring).publish("e");

    assertThat(calls).containsExactly("delegate e", "spring e");
    assertThat(guarded).containsExactly(true);
  }

  @Test
  @DisplayName("EP1: inside a unit of work, delegate only")
  void inside() {
    EventPublisher delegate = event -> calls.add("delegate " + event);

    DomainEvents.capture(
        () -> new ApplicationEventPublishingEventPublisher(delegate, spring).publish("e"));

    assertThat(calls).containsExactly("delegate e");
  }

  @Test
  @DisplayName("EP3: a failing delegate publishes nothing to Spring")
  void delegateFailure() {
    EventPublisher delegate =
        event -> {
          throw new IllegalStateException("not started");
        };

    assertThatThrownBy(
            () -> new ApplicationEventPublishingEventPublisher(delegate, spring).publish("e"))
        .hasMessage("not started");
    assertThat(calls).isEmpty();
  }
}
