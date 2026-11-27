package io.github.gkosharovdev.sagaforge.jmolecules;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.Order;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.OrderProcessingSaga;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.Orders;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.Payments;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import org.jmolecules.architecture.cqrs.Command;
import org.jmolecules.architecture.cqrs.CommandHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Startup errors over a jMolecules model name jMolecules' markers ({@code
 * specs/016-marker-spi-jmolecules} US3, SC-004), in a hand-assembled runtime.
 */
class JMoleculesStartupChecksTest {

  @Command
  @io.github.gkosharovdev.sagaforge.core.annotation.Command(delivery = CommandDelivery.OUTBOX)
  record NotifyCustomer(String customer) {}

  static final class Notifier {
    @CommandHandler
    void handle(NotifyCustomer command) {}
  }

  private static SagaRuntime.Builder runtime(Class<?>... model) {
    InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    return SagaRuntime.builder()
        .domainTypes(DomainTypes.of(new JMoleculesDomainTypeResolver()))
        .storage(new TransactionalInMemoryStorageProvider(boundary))
        .contextStore(new InMemorySagaContextStore())
        .transactions(boundary)
        .dispatcher(new RecordingEventDispatcher())
        .handlerInstances(
            new MapHandlerInstanceProvider()
                .register(new Orders())
                .register(new Payments())
                .register(new Notifier()))
        .register(model);
  }

  @Test
  @DisplayName("an aggregate with instance handlers but no loader names both jMolecules markers")
  void aggregateWithoutLoader() {
    assertThatThrownBy(
            () -> runtime(OrderProcessingSaga.class, Order.class, Payments.class).build().start())
        .isInstanceOf(SagaRuntimeStartupException.class)
        .hasMessageContaining(
            "jMolecules AggregateRoot<T, ID> Order has instance-method jMolecules @CommandHandler"
                + " methods but no @AggregateLoader and no AggregateResolver supporting it");
  }

  @Test
  @DisplayName("an outbox command over an outbox without commands names jMolecules' handler")
  void outboxCommandWithoutCommandOutbox() {
    assertThatThrownBy(
            () ->
                runtime(
                        OrderProcessingSaga.class,
                        Order.class,
                        Orders.class,
                        Payments.class,
                        Notifier.class)
                    .build()
                    .start())
        .isInstanceOf(SagaRuntimeStartupException.class)
        .hasMessageContaining(
            "Command NotifyCustomer handled by jMolecules @CommandHandler Notifier.handle is"
                + " delivered through the outbox, but the Outbox does not support commands");
  }
}
