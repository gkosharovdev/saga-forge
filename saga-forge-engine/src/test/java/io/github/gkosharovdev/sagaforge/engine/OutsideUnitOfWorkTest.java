package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ItemsReserved;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReserveItems;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingEventHandoff;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Publishing and sending outside any unit of work (specs/004-saga-engine tasks.md T049, User Story 1 scenario 7,
 * FR-016, FR-018, quickstart.md scenario 21).
 */
class OutsideUnitOfWorkTest {

    private static final String ORDER = "order-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
    private final CapturingEventHandoff handoff = CapturingEventHandoff.afterCommit();
    private final ReservationService reservations = new ReservationService();
    private SagaEngine engine;

    @BeforeEach
    void setUp() {
        ThreeStepSagaProbe.reset();
        boundary.participant(reservations);
        engine = SagaEngine.builder()
                .register(ThreeStepSaga.class, ReservationService.class, InvoiceService.class, ShipmentService.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handoff(handoff)
                .handlerInstances(new MapHandlerInstanceProvider().register(reservations)
                        .register(new InvoiceService()).register(new ShipmentService()))
                .build();
        engine.start();
    }

    @Test
    @DisplayName("publish outside a unit of work dispatches at once and starts nothing; receiving it back starts a saga")
    void publishDispatchesImmediately() {
        OrderPlaced placed = new OrderPlaced(ORDER, 250L, 3);

        engine.eventPublisher().publish(placed);

        assertThat(dispatcher.dispatched()).containsExactly(placed);
        assertThat(storage.committed().all()).isEmpty();
        assertThat(boundary.begins()).isZero();
        assertThat(handoff.received()).isEmpty();

        assertThat(engine.receive(dispatcher.dispatched().get(0)).results()).singleElement()
                .satisfies(result -> {
                    assertThat(result.started()).isTrue();
                    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
                });
        assertThat(storage.committed().all()).singleElement()
                .satisfies(instance -> assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED));
    }

    @Test
    @DisplayName("publish rejects null and non-@DomainEvent objects")
    void publishRejectsInvalidEvents() {
        assertThatThrownBy(() -> engine.eventPublisher().publish(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.eventPublisher().publish("not an event"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dispatcher.dispatched()).isEmpty();
    }

    @Test
    @DisplayName("send outside a unit of work commits the handler's change and hands its events off without a saga")
    void sendCommitsAndHandsOff() {
        engine.commandGateway().send(new ReserveItems(ORDER, 3));

        assertThat(reservations.changedAggregates()).containsExactly(ReservationService.reservationIdFor(ORDER));
        assertThat(boundary.commits()).isEqualTo(1);
        assertThat(handoff.received()).singleElement().satisfies(batch -> {
            assertThat(batch.sagaType()).isNull();
            assertThat(batch.sagaId()).isNull();
            assertThat(batch.unitOfWorkId()).isNotBlank();
            assertThat(batch.events()).containsExactly(
                    new ItemsReserved(ReservationService.reservationIdFor(ORDER), ORDER, 3));
        });
        // A handed-off event is not received back by the engine: no saga reacted to it.
        assertThat(storage.committed().all()).isEmpty();
    }

    @Test
    @DisplayName("a failing handler outside a unit of work rolls its change back, hands nothing off and rethrows")
    void sendFailureRollsBack() {
        IllegalStateException failure = new IllegalStateException("reservation failed by test");
        reservations.failNext(failure);

        assertThatThrownBy(() -> engine.commandGateway().send(new ReserveItems(ORDER, 3))).isSameAs(failure);

        assertThat(reservations.changedAggregates()).isEmpty();
        assertThat(boundary.commits()).isZero();
        assertThat(boundary.rollbacks()).isEqualTo(1);
        assertThat(boundary.isActive()).isFalse();
        assertThat(handoff.received()).isEmpty();
    }

    @Test
    @DisplayName("send rejects null and non-@Command objects before beginning a transaction")
    void sendRejectsInvalidCommands() {
        assertThatThrownBy(() -> engine.commandGateway().send(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.commandGateway().send("not a command"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(boundary.begins()).isZero();
    }

    @Test
    @DisplayName("before start, receive, send and publish throw IllegalStateException")
    void notStartedEngineRejectsWork() {
        SagaEngine notStarted = SagaEngine.builder()
                .register(ThreeStepSaga.class, ReservationService.class, InvoiceService.class, ShipmentService.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handlerInstances(new MapHandlerInstanceProvider().register(reservations))
                .build();

        assertThat(notStarted.isStarted()).isFalse();
        assertThatThrownBy(() -> notStarted.receive(new OrderPlaced(ORDER, 1L, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notStarted.commandGateway().send(new ReserveItems(ORDER, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notStarted.eventPublisher().publish(new OrderPlaced(ORDER, 1L, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(handoff.received()).isEmpty();
    }
}
