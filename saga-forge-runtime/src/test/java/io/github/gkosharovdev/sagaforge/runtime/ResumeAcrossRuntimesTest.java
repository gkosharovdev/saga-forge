package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentDeclinedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider.StoredEntry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A waiting {@link CreateBookingSaga} resumes on a later event delivered to another engine that shares only the stores
 * and the client repositories (specs/004-saga-engine tasks.md T066, User Story 3 scenarios 1 to 4, SC-004,
 * quickstart.md scenarios 7 and 8).
 */
class ResumeAcrossRuntimesTest {

    private static final String BOOKING = "booking-1";
    private static final String INVENTORY = "inventory-1";
    private static final long AMOUNT = 300L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final BookingRequestedEvent REQUESTED = new BookingRequestedEvent(BOOKING, INVENTORY,
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4), AMOUNT);
    private static final PaymentConfirmedEvent CONFIRMED =
            new PaymentConfirmedEvent(PaymentService.paymentIdFor(BOOKING), BOOKING);
    private static final PaymentDeclinedEvent DECLINED =
            new PaymentDeclinedEvent(PaymentService.paymentIdFor(BOOKING), BOOKING, "card expired");

    /**
     * What survives a process restart: the saga stores and the client's repositories. Every {@link #engine()} is a
     * fresh {@code SagaRuntime} over them, sharing nothing else.
     */
    private static final class World {

        private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
        private final InMemorySagaContextStore contextStore = new InMemorySagaContextStore();
        private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
        private final PaymentService payments = new PaymentService();
        private final InventoryRepository inventories = new InventoryRepository();
        private final BookingRepository bookings = new BookingRepository();

        World() {
            inventories.add(new Inventory(INVENTORY));
            boundary.participant(payments).participant(inventories).participant(bookings);
        }

        SagaRuntime engine() {
            AtomicInteger sagaIds = new AtomicInteger();
            SagaRuntime engine = SagaRuntime.builder()
                    .register(CreateBookingSaga.class, PaymentService.class, Inventory.class, InventoryRepository.class,
                            Booking.class, BookingRepository.class)
                    .storage(storage)
                    .contextStore(contextStore)
                    .transactions(boundary)
                    .dispatcher(dispatcher)
                    .handlerInstances(new MapHandlerInstanceProvider()
                            .register(payments).register(inventories).register(bookings))
                    .clock(CLOCK)
                    .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                    .build();
            engine.start();
            return engine;
        }

        SagaInstance onlyInstance() {
            assertThat(storage.committed().all()).hasSize(1);
            return storage.committed().all().get(0);
        }

        /** Everything a client or an operator could observe, for comparing two worlds. */
        List<Object> observable() {
            SagaInstance instance = onlyInstance();
            Map<String, Object> context = contextStore.find(instance.sagaId()).map(SagaContextRecord::values)
                    .orElse(Map.of());
            Booking booking = bookings.load(BOOKING);
            return List.of(instance, context, dispatcher.dispatched(), payments.changedAggregates(),
                    inventories.load(INVENTORY).claims(), booking == null ? "no booking" : booking.paymentId());
        }
    }

    @BeforeEach
    void resetFaults() {
        Booking.resetFaults();
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    /** Runs the first unit of work on engine A and {@code later} on a fresh engine B over the same world. */
    private static World interrupted(Object later, Consumer<World> beforeLater) {
        World world = new World();
        world.engine().receive(REQUESTED);
        beforeLater.accept(world);
        world.engine().receive(later);
        return world;
    }

    /** Runs both events on one engine. */
    private static World uninterrupted(Object later, Consumer<World> beforeLater) {
        World world = new World();
        SagaRuntime engine = world.engine();
        engine.receive(REQUESTED);
        beforeLater.accept(world);
        engine.receive(later);
        return world;
    }

    @Test
    @DisplayName("US3-1: the first unit of work commits at a wait point; the untaken steps are NOT_RUN")
    void firstUnitWaits() {
        World world = new World();

        SagaHandlingResult result = onlyResult(world.engine().receive(REQUESTED));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        SagaInstance instance = world.onlyInstance();
        assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
        CreateBookingSaga.FIRST_UNIT_STEPS.forEach(step ->
                assertThat(instance.steps().get(step).status()).isEqualTo(StepStatus.COMPLETED));
        List.of(CreateBookingSaga.PAYMENT_CONFIRMED_STEP, CreateBookingSaga.BOOKING_CONFIRMED_STEP,
                CreateBookingSaga.PAYMENT_DECLINED_STEP).forEach(step ->
                assertThat(instance.steps().get(step).status()).isEqualTo(StepStatus.NOT_RUN));
    }

    @Test
    @DisplayName("US3-2: engine B resumes on payment confirmed and completes in one new unit of work")
    void resumesOnPaymentConfirmed() {
        World world = new World();
        String sagaId = onlyResult(world.engine().receive(REQUESTED)).sagaId();

        SagaHandlingResult result = onlyResult(world.engine().receive(CONFIRMED));

        assertThat(result.sagaId()).isEqualTo(sagaId);
        assertThat(result.started()).isFalse();
        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(result.outcomeStep()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
        SagaInstance instance = world.onlyInstance();
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(instance.outcome()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
        CreateBookingSaga.CONFIRMED_PATH_STEPS.forEach(step ->
                assertThat(instance.steps().get(step).status()).isEqualTo(StepStatus.COMPLETED));
        assertThat(instance.steps().get(CreateBookingSaga.PAYMENT_DECLINED_STEP).status())
                .isEqualTo(StepStatus.NOT_RUN);
        assertThat(world.bookings.load(BOOKING)).isNotNull()
                .satisfies(booking -> assertThat(booking.claimId()).isEqualTo(Inventory.claimIdFor(BOOKING)));
        // Quickstart scenario 17: one history across both engines, in true order, numbered consecutively.
        List<StoredEntry> history = world.storage.history(sagaId);
        assertThat(history).extracting(StoredEntry::sequenceNumber).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
        assertThat(history).extracting(stored -> stored.entry().kind()).containsExactly(SagaLifecycleEventKind.CREATED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.COMPLETED);
        assertThat(history).extracting(stored -> stored.entry().stepName()).containsExactly(null,
                CreateBookingSaga.START_STEP, CreateBookingSaga.AVAILABILITY_CLAIMED_STEP,
                CreateBookingSaga.PAYMENT_CONFIRMED_STEP, CreateBookingSaga.BOOKING_CONFIRMED_STEP, null);
    }

    @Test
    @DisplayName("US3-3: engine B resumes on payment declined and releases the claim written in the first unit")
    void resumesOnPaymentDeclined() {
        World world = new World();
        world.engine().receive(REQUESTED);
        assertThat(world.inventories.load(INVENTORY).claims()).hasSize(1);

        SagaHandlingResult result = onlyResult(world.engine().receive(DECLINED));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(result.outcomeStep()).isEqualTo(CreateBookingSaga.OUTCOME_DECLINED);
        SagaInstance instance = world.onlyInstance();
        assertThat(instance.steps().get(CreateBookingSaga.PAYMENT_DECLINED_STEP).status())
                .isEqualTo(StepStatus.COMPLETED);
        CreateBookingSaga.CONFIRMED_PATH_STEPS.forEach(step ->
                assertThat(instance.steps().get(step).status()).isEqualTo(StepStatus.NOT_RUN));
        // onPaymentDeclined read the claim id from SagaContext, or ReleaseAvailability would have failed.
        assertThat(world.inventories.load(INVENTORY).claims()).isEmpty();
    }

    @Test
    @DisplayName("US3-4: a second unit of work failing on engine B keeps the first's effects and ends FAILED")
    void secondUnitFailsOnEngineB() {
        World world = new World();
        world.engine().receive(REQUESTED);
        Booking.failNext();

        SagaHandlingResult result = onlyResult(world.engine().receive(CONFIRMED));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().stepName()).isEqualTo(CreateBookingSaga.PAYMENT_CONFIRMED_STEP);
        SagaInstance instance = world.onlyInstance();
        assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(world.inventories.load(INVENTORY).claims()).hasSize(1);
        assertThat(world.payments.changedAggregates()).containsExactly(PaymentService.paymentIdFor(BOOKING));
        assertThat(world.bookings.load(BOOKING)).isNull();
        assertThat(world.contextStore.find(instance.sagaId())).hasValueSatisfying(record ->
                assertThat(record.values()).containsKeys(CreateBookingSaga.CONTEXT_CLAIM_ID,
                        CreateBookingSaga.CONTEXT_PAYMENT_ID));
    }

    @Test
    @DisplayName("SC-004: each interrupted run ends exactly as the same run on one engine")
    void interruptedEqualsUninterrupted() {
        Consumer<World> nothing = world -> { };
        Consumer<World> failBooking = world -> Booking.failNext();

        assertThat(interrupted(CONFIRMED, nothing).observable())
                .isEqualTo(uninterrupted(CONFIRMED, nothing).observable());
        assertThat(interrupted(DECLINED, nothing).observable())
                .isEqualTo(uninterrupted(DECLINED, nothing).observable());
        assertThat(interrupted(CONFIRMED, failBooking).observable())
                .isEqualTo(uninterrupted(CONFIRMED, failBooking).observable());
    }
}
