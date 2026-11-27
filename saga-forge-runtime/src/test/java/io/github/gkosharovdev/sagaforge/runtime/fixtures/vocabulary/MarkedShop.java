package io.github.gkosharovdev.sagaforge.runtime.fixtures.vocabulary;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestAggregate;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestCommand;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestEvent;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestHandles;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The canonical order saga marked with the test vocabulary only ({@code
 * specs/016-marker-spi-jmolecules} US4, Property 48): {@code @StartSaga handleOrderCreated} sends
 * {@link ProcessPayment}; {@code processPayment} sends {@link ReserveStock}, which {@link Order}
 * answers with {@link OrderCompleted} ({@code @EndSaga completeOrder}) or {@link StockUnavailable}
 * ({@code @StartCompensation rejectOrder}, compensated by {@code compensatePayment}). A payment of
 * {@code "0"} is declined. All steps on {@code associationProperty = "orderId"}. {@link
 * AnnotatedShop} is the same model with Saga Forge's annotations.
 */
public final class MarkedShop {

  private MarkedShop() {}

  @TestAggregate
  public static final class Order {

    @TestId private final String orderId;
    private final boolean inStock;

    public Order(String orderId, boolean inStock) {
      this.orderId = orderId;
      this.inStock = inStock;
    }

    @TestHandles
    public void handle(ReserveStock command) {
      DomainEvents.record(inStock ? new OrderCompleted(orderId) : new StockUnavailable(orderId));
    }

    public String orderId() {
      return orderId;
    }
  }

  public static final class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @AggregateLoader
    public Order load(String orderId) {
      return orders.get(orderId);
    }

    @AggregateSaver
    public void save(Order order) {
      orders.put(order.orderId(), order);
    }
  }

  public static final class PaymentService {

    private final List<String> refunds = new CopyOnWriteArrayList<>();

    @TestHandles
    public void handle(ProcessPayment command) {
      if (command.amount().equals("0")) {
        throw new IllegalArgumentException("Payment declined for " + command.orderId());
      }
      DomainEvents.record(new PaymentProcessed(command.orderId(), "pay-" + command.orderId()));
    }

    @TestHandles
    public void handle(RefundPayment command) {
      refunds.add(command.paymentOf());
    }

    public List<String> refunds() {
      return List.copyOf(refunds);
    }
  }

  @TestEvent(of = Order.class)
  public record OrderCreated(@TestId String orderId, String amount) {}

  @TestEvent(of = Order.class)
  public record PaymentProcessed(@TestId String orderId, String paymentId) {}

  @TestEvent(of = Order.class)
  public record OrderCompleted(@TestId String orderId) {}

  @TestEvent(of = Order.class)
  public record StockUnavailable(@TestId String orderId) {}

  @TestCommand
  public record ProcessPayment(String orderId, String amount) {}

  @TestCommand
  public record ReserveStock(@TestId String orderId) {}

  @TestCommand
  public record RefundPayment(String paymentOf) {}

  @Saga
  public static final class OrderProcessingSaga {

    @SagaId private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleOrderCreated(OrderCreated event) {
      commandGateway.send(new ProcessPayment(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void processPayment(PaymentProcessed event) {
      commandGateway.send(new ReserveStock(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void completeOrder(OrderCompleted event) {
      // Outcome step.
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void rejectOrder(StockUnavailable event) {
      // Business failure.
    }

    @SagaCompensation(forStep = "processPayment")
    public void compensatePayment() {
      commandGateway.send(new RefundPayment(sagaId));
    }
  }
}
