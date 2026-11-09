package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentInitiatedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ItemsReserved;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What {@code receive} reports when an event runs no unit of work, and what it rejects before anything runs
 * (specs/004-saga-engine tasks.md T068, User Story 3 scenario 5, FR-004, FR-006, research.md §6, quickstart.md
 * scenarios 6 and 20).
 */
class RoutingOutcomesTest {

    private static final String ORDER = "order-1";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
    private final RecordingLogger logger = new RecordingLogger();
    private SagaEngine engine;

    @BeforeEach
    void setUp() {
        ThreeStepSagaProbe.reset();
        engine = SagaEngine.builder()
                .register(ThreeStepSaga.class, ReservationService.class, InvoiceService.class, ShipmentService.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handlerInstances(new MapHandlerInstanceProvider().register(new ReservationService())
                        .register(new InvoiceService()).register(new ShipmentService()))
                .logger(logger)
                .build();
        engine.start();
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private static ItemsReserved itemsReserved(String orderId) {
        return new ItemsReserved(ReservationService.reservationIdFor(orderId), orderId, 3);
    }

    /** Nothing ran: no transaction began and no step was entered. */
    private void assertNothingRan(int beginsBefore) {
        assertThat(boundary.begins()).isEqualTo(beginsBefore);
        assertThat(ThreeStepSagaProbe.enteredSteps()).isEmpty();
    }

    @Test
    @DisplayName("US3-5: a non-start event with no correlated instance is UNMATCHED, with no effect")
    void unmatched() {
        SagaHandlingResult result = onlyResult(engine.receive(itemsReserved(ORDER)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.UNMATCHED);
        assertThat(result.sagaId()).isNull();
        assertThat(result.sagaType()).isEqualTo(ThreeStepSaga.class.getName());
        assertThat(result.started()).isFalse();
        assertThat(result.unitOfWorkId()).isNull();
        assertThat(storage.committed().all()).isEmpty();
        assertNothingRan(0);
        assertThat(logger.byEvent("saga.event.unmatched")).hasSize(1);
    }

    @Test
    @DisplayName("an event correlated only to COMPLETED instances is one UNEXPECTED for the most recently returned")
    void unexpectedForMostRecent() {
        String first = onlyResult(engine.receive(new OrderPlaced(ORDER, 250L, 3))).sagaId();
        String second = onlyResult(engine.receive(new OrderPlaced(ORDER, 250L, 3))).sagaId();
        Map<String, SagaInstance> before = committedById();
        ThreeStepSagaProbe.reset();
        int begins = boundary.begins();

        SagaHandlingResult result = onlyResult(engine.receive(itemsReserved(ORDER)));

        assertThat(first).isNotEqualTo(second);
        assertThat(result.outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
        assertThat(result.sagaId()).isEqualTo(second);
        assertThat(result.unitOfWorkId()).isNull();
        assertThat(committedById()).isEqualTo(before);
        assertNothingRan(begins);
        assertThat(logger.byEvent("saga.event.unexpected")).singleElement()
                .satisfies(logged -> assertThat(logged.keys()).containsEntry("sagaId", second));
    }

    @Test
    @DisplayName("an event for a non-start step of an instance still CREATED is NOT_HANDLED; nothing runs")
    void createdInstanceIsNotHandled() {
        SagaInstance seeded = new SagaInstance("seeded", ThreeStepSaga.class.getName(), Map.of(),
                Map.of("orderId", ORDER), SagaStatus.CREATED, NOW, NOW,
                ThreeStepSaga.STEPS_IN_EVENT_ORDER.stream()
                        .collect(Collectors.toMap(step -> step, step -> StepRecord.notRun())),
                null, null);
        storage.save(seeded, List.of());
        int begins = boundary.begins();

        SagaHandlingResult result = onlyResult(engine.receive(itemsReserved(ORDER)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.NOT_HANDLED);
        assertThat(result.sagaId()).isEqualTo("seeded");
        assertThat(result.unitOfWorkId()).isNull();
        assertThat(storage.committed().findById("seeded")).contains(seeded);
        assertThat(boundary.begins()).isEqualTo(begins + 1);
        assertThat(boundary.commits()).isZero();
        assertThat(ThreeStepSagaProbe.enteredSteps()).isEmpty();
        assertThat(logger.byEvent("saga.uow.notStarted")).hasSize(1);
    }

    @Test
    @DisplayName("an event no saga reacts to is ignored")
    void ignored() {
        HandlingReport report = engine.receive(new PaymentInitiatedEvent("payment-1", "booking-1", 300L));

        assertThat(report.ignored()).isTrue();
        assertThat(report.results()).isEmpty();
        assertNothingRan(0);
        assertThat(logger.byEvent("saga.event.ignored")).hasSize(1);
    }

    @Test
    @DisplayName("FR-006: null, a non-@DomainEvent and a missing or empty association value are rejected first")
    void invalidEventsRejected() {
        assertThatThrownBy(() -> engine.receive(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.receive("not an event")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.class.getName());
        assertThatThrownBy(() -> engine.receive(new OrderPlaced("", 250L, 3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(OrderPlaced.class.getName())
                .hasMessageContaining(ThreeStepSaga.class.getName())
                .hasMessageContaining(ThreeStepSaga.START_STEP)
                .hasMessageContaining("orderId");
        assertThatThrownBy(() -> engine.receive(new OrderPlaced(null, 250L, 3)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(storage.committed().all()).isEmpty();
        assertNothingRan(0);
    }

    private Map<String, SagaInstance> committedById() {
        return storage.committed().all().stream().collect(Collectors.toMap(SagaInstance::sagaId, instance -> instance));
    }
}
