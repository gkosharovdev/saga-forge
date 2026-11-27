package io.github.gkosharovdev.sagaforge.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The default {@code receive(EventDelivery)} of {@code specs/019-process-metadata}. */
class EventReceiverDefaultsTest {

  private final List<String> calls = new ArrayList<>();

  private final EventReceiver legacy =
      new EventReceiver() {
        @Override
        public HandlingReport receive(Object domainEvent) {
          calls.add("receive(" + domainEvent + ")");
          return null;
        }

        @Override
        public HandlingReport receive(Object domainEvent, String sourceSagaId) {
          calls.add("receive(" + domainEvent + ", " + sourceSagaId + ")");
          return null;
        }
      };

  @Test
  @DisplayName("a delivery without a source goes to receive(event)")
  void withoutSource() {
    legacy.receive(new EventDelivery("e", null, SagaMetadata.of(Map.of("k", "v"))));

    assertThat(calls).containsExactly("receive(e)");
  }

  @Test
  @DisplayName("a delivery with a source goes to receive(event, source)")
  void withSource() {
    legacy.receive(new EventDelivery("e", "saga-1", null));

    assertThat(calls).containsExactly("receive(e, saga-1)");
  }

  @Test
  @DisplayName("the delivery rejects a null event or a blank source and defaults metadata")
  void deliveryRules() {
    assertThatThrownBy(() -> new EventDelivery(null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EventDelivery("e", " ", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new EventDelivery("e", null, null).metadata()).isEqualTo(SagaMetadata.empty());
    assertThatThrownBy(() -> legacy.receive((EventDelivery) null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
