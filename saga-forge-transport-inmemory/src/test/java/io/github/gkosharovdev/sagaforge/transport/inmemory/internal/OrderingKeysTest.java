package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestAggregate;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestDomainTypeResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestEvent;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestId;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Order;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RequestPayment;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ordering keys (research §5, tasks.md T031; specs/016-marker-spi-jmolecules "Ordering key"). */
class OrderingKeysTest {

  record ParcelId(String value) {
    @Override
    public String toString() {
      return value;
    }
  }

  @TestAggregate
  static final class Parcel {
    @TestId ParcelId id;
  }

  @TestEvent
  record ParcelShipped(@TestId ParcelId parcel) {}

  @TestEvent(of = Parcel.class)
  record ParcelDelivered(@TestId String parcel) {}

  private final OrderingKeys keys = new OrderingKeys(DomainTypes.builtIn());

  @Test
  @DisplayName("an event's key is its aggregate type and identifier")
  void eventKey() {
    assertThat(keys.ofEvent(new OrderPlaced("o-1"))).isEqualTo(Order.class.getName() + "#o-1");
  }

  @Test
  @DisplayName(
      "another vocabulary's event is keyed by the aggregate it names, else its identifier type")
  void vocabularyEventKey() {
    OrderingKeys vocabularyKeys = new OrderingKeys(DomainTypes.of(new TestDomainTypeResolver()));

    assertThat(vocabularyKeys.ofEvent(new ParcelDelivered("p-1")))
        .isEqualTo(Parcel.class.getName() + "#p-1");
    assertThat(vocabularyKeys.ofEvent(new ParcelShipped(new ParcelId("p-1"))))
        .isEqualTo(ParcelId.class.getName() + "#p-1");
  }

  @Test
  @DisplayName("a command's key is its own identifier if it has one, else its saga, else none")
  void commandKey() {
    CommandId scoped = CommandId.of("s-1", "step", RequestPayment.class.getName(), 1);

    assertThat(keys.ofCommand(new ChargeCard("i-1"), scoped))
        .isEqualTo(ChargeCard.class.getName() + "#i-1");
    assertThat(keys.ofCommand(new RequestPayment("o-1"), scoped)).isEqualTo("saga:s-1");
    assertThat(
            keys.ofCommand(
                new RequestPayment("o-1"), CommandId.unscoped(RequestPayment.class.getName())))
        .isNull();
  }

  @Test
  @DisplayName("an outcome event's key is its saga, like the saga's outbox commands (011)")
  void outcomeEventKey() {
    SagaCompleted completed =
        SagaCompleted.of(
            "com.example.OrderSaga",
            "s-1",
            Map.of("orderId", "o-1"),
            Instant.parse("2026-01-01T00:00:00Z"),
            "done");

    assertThat(keys.ofEvent(completed)).isEqualTo("saga:s-1");
  }

  @Test
  @DisplayName("an object without identifier or @DomainEvent has no event key")
  void noKey() {
    assertThat(keys.ofEvent("plain")).isNull();
  }

  @Test
  @DisplayName("backoff doubles from the initial wait and stops at the maximum")
  void backoff() {
    java.time.Duration initial = java.time.Duration.ofSeconds(1);
    java.time.Duration max = java.time.Duration.ofMinutes(1);

    assertThat(Backoff.after(1, initial, max)).isEqualTo(initial);
    assertThat(Backoff.after(3, initial, max)).isEqualTo(java.time.Duration.ofSeconds(4));
    assertThat(Backoff.after(10, initial, max)).isEqualTo(max);
  }
}
