package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider.StoredEntry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The committed lifecycle history records the true order of what happened (specs/004-saga-engine tasks.md T048,
 * User Story 1 scenario 8, SC-010, quickstart.md scenario 17, committed part).
 */
class LifecycleHistoryTest {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);

    @BeforeEach
    void resetProbe() {
        ThreeStepSagaProbe.reset();
    }

    private SagaRuntime started(MapHandlerInstanceProvider instances, Class<?>... registered) {
        SagaRuntime engine = SagaRuntime.builder()
                .register(registered)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(instances)
                .build();
        engine.start();
        return engine;
    }

    private static List<SagaLifecycleEventKind> kinds(List<StoredEntry> history) {
        return history.stream().map(StoredEntry::entry).map(SagaLifecycleEntry::kind).toList();
    }

    private static List<String> stepNames(List<StoredEntry> history) {
        List<String> names = new ArrayList<>();
        history.stream().map(StoredEntry::entry).filter(entry -> entry.stepName() != null)
                .forEach(entry -> names.add(entry.stepName()));
        return names;
    }

    private static void assertConsecutiveFromZero(List<StoredEntry> history) {
        assertThat(history).extracting(StoredEntry::sequenceNumber)
                .containsExactlyElementsOf(LongStream.range(0, history.size()).boxed().toList());
    }

    @Test
    @DisplayName("ThreeStepSaga: CREATED, a STEP_COMPLETED per step in execution order, then COMPLETED(outcome)")
    void threeStepSagaHistory() {
        SagaRuntime engine = started(new MapHandlerInstanceProvider().register(new ReservationService())
                        .register(new InvoiceService()).register(new ShipmentService()),
                ThreeStepSaga.class, ReservationService.class, InvoiceService.class, ShipmentService.class);

        String sagaId = engine.receive(new OrderPlaced("order-1", 250L, 3)).results().get(0).sagaId();

        List<StoredEntry> history = storage.history(sagaId);
        assertThat(kinds(history)).containsExactly(SagaLifecycleEventKind.CREATED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.COMPLETED);
        assertThat(stepNames(history)).containsExactly(ThreeStepSaga.START_STEP, ThreeStepSaga.RESERVED_STEP,
                ThreeStepSaga.INVOICED_STEP, ThreeStepSaga.END_STEP);
        // Execution order is not alphabetical, so a sort could not have produced it.
        assertThat(stepNames(history)).isNotEqualTo(stepNames(history).stream().sorted().toList());
        assertThat(history.get(history.size() - 1).entry().outcomeStep()).isEqualTo(ThreeStepSaga.END_STEP);
        assertThat(history).allSatisfy(stored -> assertThat(stored.entry().sagaId()).isEqualTo(sagaId));
        assertConsecutiveFromZero(history);
    }

    @Test
    @DisplayName("CreateBookingSaga first unit of work: CREATED and two STEP_COMPLETED, no entry for RUNNING")
    void bookingFirstUnitHistory() {
        InventoryRepository inventories = new InventoryRepository();
        inventories.add(new Inventory("inventory-1"));
        SagaRuntime engine = started(new MapHandlerInstanceProvider().register(new PaymentService())
                        .register(inventories).register(new BookingRepository()),
                CreateBookingSaga.class, PaymentService.class, Inventory.class, InventoryRepository.class,
                Booking.class, BookingRepository.class);

        String sagaId = engine.receive(new BookingRequestedEvent("booking-1", "inventory-1",
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4), 300L)).results().get(0).sagaId();

        List<StoredEntry> history = storage.history(sagaId);
        assertThat(kinds(history)).containsExactly(SagaLifecycleEventKind.CREATED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED);
        assertThat(stepNames(history)).containsExactlyElementsOf(CreateBookingSaga.FIRST_UNIT_STEPS);
        assertConsecutiveFromZero(history);
    }
}
