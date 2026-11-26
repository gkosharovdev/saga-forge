package io.github.gkosharovdev.sagaforge.spring.transport;

import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.await;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.status;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.Order;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderApp;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderProcessingSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderRepository;
import io.github.gkosharovdev.sagaforge.spring.support.StarterApp;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Two Spring Boot applications over one Postgres ({@code specs/013-active-standby-relay} T042): one
 * relay leads, the other stands by and names the same leadership; closing the active application
 * hands the leadership on, and the standby then delivers.
 */
@Tag("container")
class MultiInstanceContainerTest {

  private static final String[] FAST = {
    "saga-forge.transport.leadership.lease-duration=600ms",
    "saga-forge.transport.leadership.renew-interval=200ms",
    "saga-forge.transport.leadership.check-interval=100ms"
  };

  private static LeadershipStatus leadership(ConfigurableApplicationContext context) {
    return context.getBean(InMemoryTransport.class).leadership();
  }

  @Test
  @DisplayName(
      "one ACTIVE and one STANDBY with the same fencing token; closing the active hands over")
  void activeStandbyAndHandover() {
    ConfigurableApplicationContext a = StarterApp.run(OrderApp.class, FAST);
    try (ConfigurableApplicationContext b = StarterApp.restart(OrderApp.class, FAST)) {
      await(() -> leadership(b).currentLeader().isPresent(), "the standby to see the leadership");
      LeadershipStatus active = leadership(a);
      LeadershipStatus standby = leadership(b);
      assertThat(active.role()).isEqualTo(RelayRole.ACTIVE);
      assertThat(standby.role()).isEqualTo(RelayRole.STANDBY);
      assertThat(standby.currentLeader())
          .map(LeaderLease::fencingToken)
          .contains(active.leader().fencingToken());
      assertThat(standby.currentLeader()).map(LeaderLease::holderId).contains(active.relayId());

      long token = active.leader().fencingToken();
      a.close();

      await(() -> leadership(b).role() == RelayRole.ACTIVE, "the standby to take over");
      assertThat(leadership(b).leader().fencingToken()).isGreaterThan(token);

      b.getBean(OrderRepository.class).save(new Order("o-1", true));
      b.getBean(EventPublisher.class)
          .publish(new OrderCreatedEvent("o-1", new BigDecimal("12.50")));
      await(
          () -> "COMPLETED".equals(status(OrderProcessingSaga.class)),
          "the saga to complete through the new leader");
    } finally {
      a.close();
    }
  }
}
