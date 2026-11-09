package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.AvailabilityClaimedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Payment;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentInitiatedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Invoice;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Reservation;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Shipment;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaStateTypeMismatchException;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * An event starts a saga and runs it to an outcome in one atomic unit of work (specs/004-saga-engine tasks.md T047,
 * User Story 1 scenarios 1 to 6, quickstart.md scenarios 2 and 4).
 */
class SagaEngineHappyPathTest {

    private static final String ORDER = "order-1";
    private static final long AMOUNT = 250L;
    private static final int QUANTITY = 3;

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contextStore = new InMemorySagaContextStore();
    private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();

    @BeforeEach
    void resetFixtures() {
        ThreeStepSagaProbe.reset();
    }

    private SagaEngine.Builder engineOver(HandlerInstanceProvider instances) {
        return SagaEngine.builder()
                .storage(storage)
                .contextStore(contextStore)
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handlerInstances(instances);
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    @Nested
    @DisplayName("ThreeStepSaga")
    class ThreeStep {

        private final ReservationService reservations = new ReservationService();
        private final InvoiceService invoices = new InvoiceService();
        private final ShipmentService shipments = new ShipmentService();
        private final MapHandlerInstanceProvider handlers = new MapHandlerInstanceProvider()
                .register(reservations).register(invoices).register(shipments);

        private SagaEngine started(HandlerInstanceProvider instances) {
            boundary.participant(reservations).participant(invoices).participant(shipments);
            SagaEngine engine = engineOver(instances)
                    .register(ThreeStepSaga.class, ReservationService.class, InvoiceService.class,
                            ShipmentService.class)
                    .build();
            engine.start();
            return engine;
        }

        private HandlingReport placeOrder(SagaEngine engine) {
            return engine.receive(new OrderPlaced(ORDER, AMOUNT, QUANTITY));
        }

        @Test
        @DisplayName("US1-1: one instance COMPLETED with its outcome, every step COMPLETED, effects committed")
        void completesInOneUnitOfWork() {
            SagaHandlingResult result = onlyResult(placeOrder(started(handlers)));

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
            assertThat(result.started()).isTrue();
            assertThat(result.outcomeStep()).isEqualTo(ThreeStepSaga.END_STEP);
            assertThat(result.sagaType()).isEqualTo(ThreeStepSaga.class.getName());
            assertThat(result.unitOfWorkId()).isNotNull();
            assertThat(result.handoffFailures()).isEmpty();

            assertThat(storage.committed().all()).hasSize(1);
            SagaInstance instance = committed(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(ThreeStepSaga.END_STEP);
            assertThat(instance.failure()).isNull();
            assertThat(instance.associations()).isEqualTo(Map.of("orderId", ORDER));
            assertThat(instance.steps().values()).allSatisfy(step -> assertThat(step.handledCommands())
                    .hasSizeLessThanOrEqualTo(1)
                    .allSatisfy(id -> assertThat(id.sagaId()).isEqualTo(result.sagaId())));
            Map<String, StepRecord> withoutCommands = new java.util.HashMap<>();
            instance.steps().forEach((name, step) -> withoutCommands.put(name,
                    StepRecord.completed(step.touchedAggregates(), step.attempts(), List.of())));
            assertThat(withoutCommands).isEqualTo(Map.of(
                    ThreeStepSaga.START_STEP, StepRecord.completed(List.of(AggregateReference.of(Reservation.class,
                            ReservationService.reservationIdFor(ORDER)))),
                    ThreeStepSaga.RESERVED_STEP, StepRecord.completed(List.of(AggregateReference.of(Invoice.class,
                            InvoiceService.invoiceIdFor(ORDER)))),
                    ThreeStepSaga.INVOICED_STEP, StepRecord.completed(List.of(AggregateReference.of(Shipment.class,
                            ShipmentService.shipmentIdFor(ORDER)))),
                    ThreeStepSaga.END_STEP, StepRecord.completed(List.of())));

            assertThat(reservations.changedAggregates()).containsExactly(ReservationService.reservationIdFor(ORDER));
            assertThat(invoices.changedAggregates()).containsExactly(InvoiceService.invoiceIdFor(ORDER));
            assertThat(shipments.changedAggregates()).containsExactly(ShipmentService.shipmentIdFor(ORDER));
            assertThat(dispatcher.dispatched()).isEmpty();
            assertThat(dispatcher.registeredSagaTypes()).containsExactly(ThreeStepSaga.class);
        }

        @Test
        @DisplayName("US1-2: an observer reading committed state from inside a step sees nothing of the running unit")
        void observerSeesNoPartialState() throws Exception {
            List<Optional<SagaInstance>> observed = new ArrayList<>();
            HandlerInstanceProvider observing = handlerType -> {
                observed.add(readCommittedOnAnotherThread());
                return handlers.instanceOf(handlerType);
            };

            SagaHandlingResult result = onlyResult(placeOrder(started(observing)));

            // One observation per handler call: inside the start step, the second and the third step.
            assertThat(observed).hasSize(3).allSatisfy(seen -> assertThat(seen).hasValueSatisfying(instance -> {
                assertThat(instance.sagaId()).isEqualTo(result.sagaId());
                assertThat(instance.status()).isEqualTo(SagaStatus.CREATED);
                assertThat(instance.outcome()).isNull();
                assertThat(instance.steps().values()).extracting(StepRecord::status).containsOnly(StepStatus.NOT_RUN);
            }));
            assertThat(committed(result.sagaId()).status()).isEqualTo(SagaStatus.COMPLETED);
        }

        private Optional<SagaInstance> readCommittedOnAnotherThread() {
            try {
                return CompletableFuture.supplyAsync(() -> storage.committed().all().stream().findFirst())
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("observer thread failed", e);
            }
        }

        @Test
        @DisplayName("US1-3: a later step reads the SagaContext value an earlier step wrote")
        void laterStepReadsContext() {
            SagaHandlingResult result = onlyResult(placeOrder(started(handlers)));

            // onItemsReserved read the amount onOrderPlaced wrote, or it would have thrown and nothing committed.
            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
            assertThat(contextStore.find(result.sagaId())).hasValueSatisfying(
                    record -> assertThat(record.values()).isEqualTo(Map.of(ThreeStepSaga.CONTEXT_AMOUNT, AMOUNT)));
        }

        @Test
        @DisplayName("US1-6: steps run in event order, not declaration order")
        void stepsRunInEventOrder() {
            placeOrder(started(handlers));

            assertThat(ThreeStepSagaProbe.enteredSteps()).containsExactlyElementsOf(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
        }
    }

    @Nested
    @DisplayName("SagaContext type safety across steps")
    class ContextTypeSafety {

        @Test
        @DisplayName("US1-3: reading a value with an incompatible type fails the unit of work and completes nothing")
        void incompatibleTypeFails() {
            SagaEngine engine = engineOver(new MapHandlerInstanceProvider().register(new TicketService()))
                    .register(TypedContextSaga.class, Ticket.class, TicketService.class)
                    .build();
            engine.start();

            SagaHandlingResult result = onlyResult(engine.receive(new TicketOpened("ticket-1")));

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
            assertThat(result.failure().stepName()).isEqualTo("onTicketPriced");
            assertThat(result.failure().causeType()).isEqualTo(SagaStateTypeMismatchException.class.getName());
            assertThat(storage.committed().all()).singleElement().satisfies(instance -> {
                assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
                assertThat(instance.steps().values()).extracting(StepRecord::status)
                        .doesNotContain(StepStatus.COMPLETED);
            });
            assertThat(contextStore.find(storage.committed().all().get(0).sagaId())).hasValueSatisfying(
                    record -> assertThat(record.values()).isEqualTo(Map.of(TypedContextSaga.PRICE, "12.50")));
        }
    }

    @Nested
    @DisplayName("CreateBookingSaga first unit of work")
    class BookingFirstUnit {

        private static final String BOOKING = "booking-1";
        private static final String INVENTORY = "inventory-1";

        private final PaymentService payments = new PaymentService();
        private final InventoryRepository inventories = new InventoryRepository();
        private final BookingRepository bookings = new BookingRepository();

        private SagaEngine started() {
            inventories.add(new Inventory(INVENTORY));
            boundary.participant(payments).participant(inventories).participant(bookings);
            SagaEngine engine = engineOver(new MapHandlerInstanceProvider()
                    .register(payments).register(inventories).register(bookings))
                    .register(CreateBookingSaga.class, PaymentService.class, Inventory.class,
                            InventoryRepository.class, Booking.class, BookingRepository.class)
                    .build();
            engine.start();
            return engine;
        }

        private HandlingReport requestBooking(SagaEngine engine) {
            return engine.receive(new BookingRequestedEvent(BOOKING, INVENTORY, LocalDate.of(2026, 3, 1),
                    LocalDate.of(2026, 3, 4), 300L));
        }

        @Test
        @DisplayName("US1-5: Inventory is loaded and saved through InventoryRepository; the saga then waits")
        void inventoryLoadedAndSavedThroughRepository() {
            SagaHandlingResult result = onlyResult(requestBooking(started()));

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
            assertThat(result.started()).isTrue();
            assertThat(result.outcomeStep()).isNull();
            assertThat(inventories.saveCount()).isEqualTo(1);
            assertThat(inventories.load(INVENTORY).claim(Inventory.claimIdFor(BOOKING))).isPresent();
            assertThat(payments.find(PaymentService.paymentIdFor(BOOKING))).isPresent();

            SagaInstance instance = committed(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(CreateBookingSaga.FIRST_UNIT_STEPS).allSatisfy(step ->
                    assertThat(instance.steps().get(step).status()).isEqualTo(StepStatus.COMPLETED));
            assertThat(instance.steps().get(CreateBookingSaga.AVAILABILITY_CLAIMED_STEP).touchedAggregates())
                    .containsExactly(AggregateReference.of(
                            Payment.class,
                            PaymentService.paymentIdFor(BOOKING)));
            assertThat(instance.steps().get(CreateBookingSaga.START_STEP).touchedAggregates())
                    .containsExactly(AggregateReference.of(Inventory.class, INVENTORY));
        }

        @Test
        @DisplayName("US1-4: PaymentInitiatedEvent reaches the dispatcher exactly once, after commit")
        void bufferedEventDispatchedOnceAfterCommit() {
            List<String> atDispatch = new ArrayList<>();
            dispatcher.onDispatch(event -> {
                atDispatch.add("transaction active: " + boundary.current().isPresent());
                storage.committed().all().forEach(instance -> atDispatch.add("committed: " + instance.status()));
            });

            requestBooking(started());

            assertThat(dispatcher.dispatched()).singleElement().isInstanceOf(PaymentInitiatedEvent.class)
                    .isEqualTo(new PaymentInitiatedEvent(PaymentService.paymentIdFor(BOOKING), BOOKING, 300L));
            assertThat(dispatcher.dispatched()).noneMatch(AvailabilityClaimedEvent.class::isInstance);
            assertThat(atDispatch).containsExactly("transaction active: false", "committed: RUNNING");
        }
    }

    // ---- TypedContextSaga: the start step writes a String, the next step reads it as a Long

    /** Aggregate named by the ticket events. */
    @Aggregate
    public static final class Ticket {

        @AggregateIdentifier
        private String ticketId;
    }

    /** Start event of {@link TypedContextSaga}. */
    @DomainEvent(aggregate = Ticket.class)
    public record TicketOpened(@AggregateIdentifier String ticketId) {
    }

    /** Recorded by {@link TicketService}; runs the end step in-process. */
    @DomainEvent(aggregate = Ticket.class)
    public record TicketPriced(@AggregateIdentifier String ticketId) {
    }

    /** Sent by the start step. */
    @Command
    public record PriceTicket(String ticketId) {
    }

    /** Service-hosted handler of {@link PriceTicket}. */
    public static final class TicketService {

        @CommandHandler
        public void handle(PriceTicket command) {
            DomainEvents.record(new TicketPriced(command.ticketId()));
        }
    }

    /** Writes the price as a {@code String} and reads it back as a {@code Long} in the next step. */
    @Saga
    public static final class TypedContextSaga {

        static final String PRICE = "price";

        @SagaId
        private String sagaId;

        private SagaContext context;

        private CommandGateway commandGateway;

        @StartSaga
        @SagaStep(associationProperty = "ticketId")
        public void onTicketOpened(TicketOpened event) {
            context.putState(PRICE, "12.50");
            commandGateway.send(new PriceTicket(event.ticketId()));
        }

        @EndSaga
        @SagaStep(associationProperty = "ticketId")
        public void onTicketPriced(TicketPriced event) {
            context.getState(PRICE, Long.class);
        }
    }
}
