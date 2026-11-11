package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Order;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RequestPayment;

/** Ordering keys (research §5, tasks.md T031). */
class OrderingKeysTest {

    @Test
    @DisplayName("an event's key is its aggregate type and identifier")
    void eventKey() {
        assertThat(OrderingKeys.ofEvent(new OrderPlaced("o-1"))).isEqualTo(Order.class.getName() + "#o-1");
    }

    @Test
    @DisplayName("a command's key is its own identifier if it has one, else its saga, else none")
    void commandKey() {
        CommandId scoped = CommandId.of("s-1", "step", RequestPayment.class.getName(), 1);

        assertThat(OrderingKeys.ofCommand(new ChargeCard("i-1"), scoped))
                .isEqualTo(ChargeCard.class.getName() + "#i-1");
        assertThat(OrderingKeys.ofCommand(new RequestPayment("o-1"), scoped)).isEqualTo("saga:s-1");
        assertThat(OrderingKeys.ofCommand(new RequestPayment("o-1"),
                CommandId.unscoped(RequestPayment.class.getName()))).isNull();
    }

    @Test
    @DisplayName("an object without identifier or @DomainEvent has no event key")
    void noKey() {
        assertThat(OrderingKeys.ofEvent("plain")).isNull();
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
