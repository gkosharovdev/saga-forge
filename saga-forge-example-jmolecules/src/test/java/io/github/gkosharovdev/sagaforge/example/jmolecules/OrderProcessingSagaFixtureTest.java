package io.github.gkosharovdev.sagaforge.example.jmolecules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.example.jmolecules.application.OrderEventSource;
import io.github.gkosharovdev.sagaforge.example.jmolecules.application.OrderProcessingSaga;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Order;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderCreated;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderId;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Payments;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.ProcessPayment;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.RefundPayment;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.ReserveStock;
import io.github.gkosharovdev.sagaforge.jmolecules.JMoleculesDomainTypeResolver;
import io.github.gkosharovdev.sagaforge.test.SagaFixture;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The saga over the jMolecules domain with the real handlers ({@code
 * specs/016-marker-spi-jmolecules} SC-008): {@link Payments} announces a charge, the {@link Order}
 * holds the outcome of a stock reservation and {@link OrderEventSource} takes it, as in the
 * application.
 */
class OrderProcessingSagaFixtureTest {

  private static final OrderId ORDER = new OrderId("o-1");
  private static final BigDecimal AMOUNT = new BigDecimal("25.00");

  private final Payments payments = new Payments(DomainEvents::record);

  private SagaFixture fixture() {
    return SagaFixture.of(OrderProcessingSaga.class, Order.class)
        .domainTypes(DomainTypes.of(new JMoleculesDomainTypeResolver()))
        .handlers(payments)
        .aggregateEventSource(new OrderEventSource())
        .build();
  }

  @Test
  @DisplayName("an order in stock is charged, reserved and completed")
  void completes() {
    SagaFixture fixture = fixture();
    fixture.givenAggregates(Order.place(ORDER, true));

    fixture
        .when(new OrderCreated(ORDER, AMOUNT))
        .expectCommands(new ProcessPayment(ORDER, AMOUNT), new ReserveStock(ORDER))
        .expectContext("orderAmount", "25.00")
        .expectEndedWith("completeOrder");
    assertThat(payments.charges()).containsEntry("pay-o-1", AMOUNT);
    assertThat(fixture.aggregate(Order.class, ORDER))
        .hasValueSatisfying(
            order -> {
              assertThat(order.status()).isEqualTo(Order.Status.COMPLETED);
              assertThat(order.domainEvents()).isEmpty();
            });
  }

  @Test
  @DisplayName("an order out of stock compensates its payment")
  void compensates() {
    SagaFixture fixture = fixture();
    fixture.givenAggregates(Order.place(ORDER, false));

    fixture
        .when(new OrderCreated(ORDER, AMOUNT))
        .expectCompensations("compensatePayment")
        .expectCompensatedAfter("rejectOrder")
        .expectStatus(SagaStatus.COMPENSATED);
    assertThat(fixture.commands())
        .map(command -> command.command())
        .hasAtLeastOneElementOfType(RefundPayment.class);
    assertThat(payments.refunds()).containsValue(AMOUNT);
  }

  @Test
  @DisplayName("a declined charge fails the saga before anything committed")
  void declined() {
    SagaFixture fixture = fixture();
    fixture.givenAggregates(Order.place(ORDER, true));

    fixture.when(new OrderCreated(ORDER, BigDecimal.ZERO)).expectStatus(SagaStatus.FAILED);
    assertThat(payments.charges()).isEmpty();
  }
}
