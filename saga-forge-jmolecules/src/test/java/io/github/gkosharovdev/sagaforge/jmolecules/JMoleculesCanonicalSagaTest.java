package io.github.gkosharovdev.sagaforge.jmolecules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.Order;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.OrderCreated;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.OrderId;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.OrderProcessingSaga;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.Orders;
import io.github.gkosharovdev.sagaforge.jmolecules.canonical.Payments;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical saga over a domain marked only with jMolecules, in a hand-assembled runtime
 * (specs/016-marker-spi-jmolecules US1 scenarios 1, 2 and 4, Property 48): it reaches the same
 * steps, statuses and outcomes as {@code saga-forge-example}'s Saga Forge-annotated model.
 */
class JMoleculesCanonicalSagaTest {

  private static final OrderId ORDER = new OrderId("o-1");
  private static final BigDecimal AMOUNT = new BigDecimal("100.00");

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);

  private SagaRuntime runtime(DomainTypes domainTypes, Orders orders, Payments payments) {
    SagaRuntime runtime =
        SagaRuntime.builder()
            .domainTypes(domainTypes)
            .storage(storage)
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .handlerInstances(new MapHandlerInstanceProvider().register(orders).register(payments))
            .register(OrderProcessingSaga.class, Order.class, Orders.class, Payments.class)
            .build();
    runtime.start();
    return runtime;
  }

  private static DomainTypes jMolecules() {
    return DomainTypes.of(new JMoleculesDomainTypeResolver());
  }

  private SagaHandlingResult place(SagaRuntime runtime) {
    var results = runtime.receive(new OrderCreated(ORDER, AMOUNT)).results();
    assertThat(results).hasSize(1);
    return results.get(0);
  }

  private SagaInstance committed(SagaHandlingResult result) {
    return storage.committed().findById(result.sagaId()).orElseThrow();
  }

  @Test
  @DisplayName("US1-1: the order is paid, its stock reserved, and the saga completes")
  void completes() {
    Orders orders = new Orders().add(new Order(ORDER, true));
    Payments payments = new Payments();

    SagaHandlingResult result = place(runtime(jMolecules(), orders, payments));

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
    assertThat(result.outcomeStep()).isEqualTo("completeOrder");
    SagaInstance instance = committed(result);
    assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
    assertThat(instance.associations()).containsEntry("orderId", "o-1");
    assertThat(instance.steps())
        .hasEntrySatisfying("handleOrderCreated", completed())
        .hasEntrySatisfying("processPayment", completed())
        .hasEntrySatisfying("completeOrder", completed())
        .hasEntrySatisfying(
            "rejectOrder", step -> assertThat(step.status()).isEqualTo(StepStatus.NOT_RUN));
    assertThat(instance.steps().values())
        .flatMap(StepRecord::touchedAggregates)
        .contains(new AggregateReference(Order.class.getName(), "o-1"));
    assertThat(orders.load(ORDER).isReserved()).isTrue();
    assertThat(payments.charged()).containsExactly("pay-o-1");
  }

  private static java.util.function.Consumer<StepRecord> completed() {
    return step -> assertThat(step.status()).isEqualTo(StepStatus.COMPLETED);
  }

  @Test
  @DisplayName("US1-2: stock unavailable after payment compensates the payment")
  void compensates() {
    Payments payments = new Payments();

    SagaHandlingResult result =
        place(runtime(jMolecules(), new Orders().add(new Order(ORDER, false)), payments));

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
    SagaInstance instance = committed(result);
    assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(instance.compensationTrigger().stepName()).isEqualTo("rejectOrder");
    assertThat(payments.refunds()).singleElement().asString().startsWith("pay-o-1@");
  }

  @Test
  @DisplayName("an unclassified payment failure rolls the unit of work back and the saga fails")
  void fails() {
    Payments payments = new Payments().failing();

    SagaHandlingResult result =
        place(runtime(jMolecules(), new Orders().add(new Order(ORDER, true)), payments));

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
    assertThat(committed(result).status()).isEqualTo(SagaStatus.FAILED);
    assertThat(payments.charged()).isEmpty();
  }

  @Test
  @DisplayName("US1-4: without the vocabulary, startup fails naming Saga Forge's @DomainEvent")
  void withoutTheVocabulary() {
    Orders orders = new Orders();
    Payments payments = new Payments();

    assertThatThrownBy(() -> runtime(DomainTypes.builtIn(), orders, payments))
        .isInstanceOf(SagaRuntimeStartupException.class)
        .hasMessageContaining(
            "@SagaStep method handleOrderCreated parameter type OrderCreated must be annotated with"
                + " @DomainEvent");
  }

  @Test
  @DisplayName("the jMolecules events are step events (the 014 inbound route and fan-out see them)")
  void eventTypes() {
    SagaRuntime runtime = runtime(jMolecules(), new Orders(), new Payments());

    assertThat(runtime.eventTypes())
        .extracting(Class::getSimpleName)
        .contains("OrderCreated", "PaymentProcessed", "OrderCompleted", "StockUnavailable");
  }
}
