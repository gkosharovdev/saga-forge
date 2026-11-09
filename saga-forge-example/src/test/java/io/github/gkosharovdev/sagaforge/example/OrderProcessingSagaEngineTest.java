package io.github.gkosharovdev.sagaforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TestTransport;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider.StoredEntry;
import io.github.gkosharovdev.sagaforge.example.events.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.example.events.StockUnavailableEvent;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canonical {@link OrderProcessingSaga} runs on the saga engine, driven only through a test transport
 * (specs/004-saga-engine tasks.md T101, FR-040, SC-007, User Story 1 scenarios 1, 5, 7 and 8, quickstart.md scenario 1).
 *
 * <p>{@link OrderRepository} keeps its orders in a plain map that no transaction rolls back, so aggregate rollback is
 * not asserted here; the engine tests cover it with snapshotting doubles (research.md §14).</p>
 */
class OrderProcessingSagaEngineTest {

    private static final String ORDER = "order-1";
    private static final BigDecimal AMOUNT = new BigDecimal("99.90");

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contextStore = new InMemorySagaContextStore();
    private final TestTransport transport = new TestTransport();
    private final OrderRepository orders = new OrderRepository();
    private final PaymentProvider provider = new PaymentProvider();
    private final List<java.time.Duration> waits = new java.util.ArrayList<>();

    private SagaEngine startedEngine() {
        SagaEngine engine = SagaEngine.builder()
                .register(OrderProcessingSaga.class, Order.class, OrderRepository.class, PaymentService.class)
                .storage(storage)
                .contextStore(contextStore)
                .transactions(boundary)
                .dispatcher(transport)
                .handlerInstances(new MapHandlerInstanceProvider().register(orders)
                        .register(new PaymentService(provider)))
                .retryPause(waits::add)
                .build();
        engine.start();
        transport.connect(engine);
        return engine;
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    @Test
    @DisplayName("an OrderCreatedEvent runs the saga to completeOrder in one unit of work")
    void completesInOneUnitOfWork() {
        startedEngine();
        orders.save(new Order(ORDER));

        SagaHandlingResult result = onlyResult(transport.deliver(new OrderCreatedEvent(ORDER, AMOUNT)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(result.started()).isTrue();
        assertThat(result.outcomeStep()).isEqualTo("completeOrder");
        SagaInstance instance = committed(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(instance.steps()).allSatisfy((step, record) -> assertThat(record.status())
                .isEqualTo(step.equals("rejectOrder") ? StepStatus.NOT_RUN : StepStatus.COMPLETED));
        assertThat(instance.steps().get("handleOrderCreated").touchedAggregates()).singleElement()
                .extracting(AggregateReference::aggregateType).isEqualTo(Payment.class.getName());
        assertThat(instance.steps().get("processPayment").touchedAggregates())
                .containsExactly(AggregateReference.of(Order.class, ORDER));
        assertThat(instance.steps().get("completeOrder").touchedAggregates()).isEmpty();
        assertThat(orders.load(ORDER).status()).isEqualTo(Order.Status.COMPLETED);
        assertThat(transport.dispatched()).isEmpty();

        List<SagaLifecycleEntry> history = storage.history(result.sagaId()).stream().map(StoredEntry::entry).toList();
        assertThat(history).extracting(SagaLifecycleEntry::kind).containsExactly(SagaLifecycleEventKind.CREATED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.COMPLETED);
        assertThat(history).extracting(SagaLifecycleEntry::stepName)
                .containsExactly(null, "handleOrderCreated", "processPayment", "completeOrder", null);
    }

    @Test
    @DisplayName("without the Order, processPayment's unit of work fails and the order amount stays readable")
    void missingOrderFailsInProcessPayment() {
        startedEngine();

        SagaHandlingResult result = onlyResult(transport.deliver(new OrderCreatedEvent(ORDER, AMOUNT)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().stepName()).isEqualTo("processPayment");
        assertThat(result.failure().causeType()).isEqualTo(AggregateNotFoundException.class.getName());
        SagaInstance instance = committed(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(instance.steps().values()).extracting(StepRecord::status).containsOnly(StepStatus.NOT_RUN);
        assertThat(contextStore.find(result.sagaId())).map(SagaContextRecord::values)
                .hasValueSatisfying(values -> assertThat(values).containsEntry("orderAmount", AMOUNT));
    }

    @Test
    @DisplayName("an OrderCreatedEvent published outside a unit of work reaches the saga through the transport")
    void publishedEventStartsTheSaga() {
        SagaEngine engine = startedEngine();
        transport.loopBack();
        orders.save(new Order(ORDER));

        engine.eventPublisher().publish(new OrderCreatedEvent(ORDER, AMOUNT));

        assertThat(transport.dispatched()).containsExactly(new OrderCreatedEvent(ORDER, AMOUNT));
        SagaHandlingResult result = onlyResult(transport.reports().get(0));
        assertThat(result.started()).isTrue();
        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(storage.committed().all()).singleElement()
                .satisfies(instance -> assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED));
    }

    @Test
    @DisplayName("the engine registers the saga type with the transport before any event")
    void sagaTypeRegistered() {
        startedEngine();

        assertThat(transport.registeredSagaTypes()).containsExactly(OrderProcessingSaga.class);
        assertThat(contextStore.all()).isEmpty();
    }

    @Test
    @DisplayName("005: a payment provider timeout is retried; the customer is charged once and the order completes")
    void timeoutIsRetriedWithoutChargingTwice() {
        startedEngine();
        orders.save(new Order(ORDER));
        provider.timeOutNext(1);

        SagaHandlingResult result = onlyResult(transport.deliver(new OrderCreatedEvent(ORDER, AMOUNT)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(provider.charges()).hasSize(1);
        assertThat(provider.charges().values().iterator().next()).isEqualByComparingTo(AMOUNT);
        assertThat(waits).containsExactly(java.time.Duration.ofMillis(200));
        SagaInstance instance = committed(result.sagaId());
        assertThat(instance.steps()).allSatisfy((step, record) -> assertThat(record.attempts())
                .isEqualTo(step.equals("rejectOrder") ? 0 : 2));
        assertThat(instance.steps().get("handleOrderCreated").handledCommands()).singleElement()
                .satisfies(id -> assertThat("payment-" + id.value()).isEqualTo(
                        provider.charges().keySet().iterator().next()));
    }

    @Test
    @DisplayName("005: a redelivered PaymentProcessedEvent after completion is a DUPLICATE; nothing runs again")
    void redeliveryIsADuplicate() {
        startedEngine();
        orders.save(new Order(ORDER));
        SagaHandlingResult result = onlyResult(transport.deliver(new OrderCreatedEvent(ORDER, AMOUNT)));
        String paymentId = provider.charges().keySet().iterator().next();

        SagaHandlingResult repeated = onlyResult(transport.deliver(
                new io.github.gkosharovdev.sagaforge.example.events.PaymentProcessedEvent(paymentId, ORDER, AMOUNT)));

        assertThat(repeated.outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(repeated.sagaId()).isEqualTo(result.sagaId());
        assertThat(committed(result.sagaId()).status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("006: stock unavailable after the payment refunds it once and the saga ends COMPENSATED")
    void stockShortageRefundsThePayment() {
        startedEngine();
        orders.save(new Order(ORDER, false));

        SagaHandlingResult result = onlyResult(transport.deliver(new OrderCreatedEvent(ORDER, AMOUNT)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        String paymentId = provider.charges().keySet().iterator().next();
        assertThat(provider.refunds()).containsExactly(java.util.Map.entry(paymentId, AMOUNT));
        assertThat(orders.load(ORDER).status()).isEqualTo(Order.Status.OUT_OF_STOCK);
        SagaInstance instance = committed(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(instance.compensationTrigger().stepName()).isEqualTo("rejectOrder");
        assertThat(instance.compensationTrigger().eventType()).isEqualTo(StockUnavailableEvent.class.getName());
        assertThat(instance.steps().get("processPayment").compensation().status())
                .isEqualTo(CompensationStatus.COMPENSATED);
        assertThat(instance.steps().get("handleOrderCreated").compensation().status())
                .isEqualTo(CompensationStatus.NOTHING_TO_COMPENSATE);
        assertThat(instance.steps().get("completeOrder").status()).isEqualTo(StepStatus.NOT_RUN);
    }

    @Test
    @DisplayName("006: a repeated StockUnavailableEvent after COMPENSATED is a DUPLICATE; no second refund")
    void repeatedShortageIsADuplicate() {
        startedEngine();
        orders.save(new Order(ORDER, false));
        SagaHandlingResult result = onlyResult(transport.deliver(new OrderCreatedEvent(ORDER, AMOUNT)));

        SagaHandlingResult repeated = onlyResult(transport.deliver(new StockUnavailableEvent(ORDER)));

        assertThat(repeated.outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(provider.refunds()).hasSize(1);
        assertThat(committed(result.sagaId()).status()).isEqualTo(SagaStatus.COMPENSATED);
    }
}
