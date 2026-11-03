package io.github.gkosharovdev.sagaforge.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.AvailabilityClaimedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InitiatePayment;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Payment;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentInitiatedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Entry;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.SagaTestHarness;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Command routing through the command gateway (spec 002 User Story 2, acceptance scenarios 6 to 11 and 13;
 * FR-004 touched aggregates, FR-011 to FR-017), driven by {@link CreateBookingSaga} over the booking fixtures
 * and the {@link SagaTestHarness}.
 *
 * <p>Handler kinds exercised: {@link Inventory} (instance-method handler on a plain {@code @Aggregate}, loaded and
 * saved through {@link InventoryRepository}), {@link PaymentService} (service-hosted, records with
 * {@code DomainEvents.record}), and {@link Booking} (creation constructor, saved through
 * {@link BookingRepository}).</p>
 */
class CommandRoutingTest {

    private static final String BOOKING = "b-1";
    private static final String INVENTORY = "inv-1";
    private static final long AMOUNT = 12_500L;
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 3, 1);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 3, 4);
    private static final String CLAIM = Inventory.claimIdFor(BOOKING);
    private static final String PAYMENT = PaymentService.paymentIdFor(BOOKING);

    private InventoryRepository inventoryRepository;
    private BookingRepository bookingRepository;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        Booking.resetFaults();
        inventoryRepository = new InventoryRepository();
        bookingRepository = new BookingRepository();
        paymentService = new PaymentService();
    }

    private SagaTestHarness bookingHarness() {
        return SagaTestHarness.builder()
                .saga(CreateBookingSaga.class)
                .handler(paymentService)
                .aggregate(Inventory.class)
                .repository(inventoryRepository)
                .aggregate(Booking.class)
                .repository(bookingRepository)
                .build();
    }

    private static BookingRequestedEvent bookingRequested() {
        return new BookingRequestedEvent(BOOKING, INVENTORY, CHECK_IN, CHECK_OUT, AMOUNT);
    }

    private void seedInventory() {
        inventoryRepository.add(new Inventory(INVENTORY));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Test-local saga for scenario 7 (service event triggers the next step) and scenario 9 (caught failure)
    // ---------------------------------------------------------------------------------------------------------

    /** Start event of {@link ChargingSaga}. */
    @DomainEvent(aggregate = Booking.class)
    public record ChargeRequested(@AggregateIdentifier String bookingId, long amount) {
    }

    /**
     * Minimal saga over the fixture {@link PaymentService}: the start step sends {@link InitiatePayment} and
     * deliberately catches any failure; the {@code @EndSaga} step reacts to the service's
     * {@link PaymentInitiatedEvent}.
     */
    @Saga
    public static class ChargingSaga {
        static final String START_STEP = "onChargeRequested";
        static final String END_STEP = "onPaymentInitiated";

        @SagaId
        private String sagaId;
        private CommandGateway commandGateway;

        @StartSaga
        @SagaStep(associationProperty = "bookingId")
        public void onChargeRequested(ChargeRequested event) {
            try {
                commandGateway.send(new InitiatePayment(event.bookingId(), event.amount()));
            } catch (RuntimeException swallowed) {
                // Deliberately swallowed: FR-012 says the unit of work still rolls back.
            }
        }

        @EndSaga
        @SagaStep(associationProperty = "bookingId")
        public void onPaymentInitiated(PaymentInitiatedEvent event) {
            // Outcome step: nothing left to dispatch.
        }
    }

    private SagaTestHarness chargingHarness() {
        return SagaTestHarness.builder().saga(ChargingSaga.class).handler(paymentService).build();
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Scenario 6: an aggregate-hosted handler runs inside the unit of work")
    class AggregateHostedHandler {

        @Test
        void inventoryIsAPlainClassWithNoSagaForgeSupertype() {
            assertThat(Inventory.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(Inventory.class.getInterfaces()).isEmpty();
        }

        @Test
        void loadedByLoaderSavedOnceBySaverAndItsEventTriggersTheNextStepInTheSameUnitOfWork() {
            seedInventory();
            SagaTestHarness harness = bookingHarness();

            UnitOfWorkResult result = harness.deliver(bookingRequested());

            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(result.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.FIRST_UNIT_STEPS);
            assertThat(inventoryRepository.saveCount()).isEqualTo(1);
            assertThat(inventoryRepository.load(INVENTORY).claim(CLAIM)).isPresent();
            AvailabilityClaimedEvent claimed = new AvailabilityClaimedEvent(INVENTORY, BOOKING, CLAIM);
            assertThat(harness.eventLog().entries(Kind.IN_PROCESS))
                    .extracting(Entry::event, Entry::sagaId, Entry::stepName)
                    .containsExactly(tuple(claimed, result.sagaId(), CreateBookingSaga.AVAILABILITY_CLAIMED_STEP));
            assertThat(result.dispatched()).doesNotContain(claimed);
        }
    }

    @Nested
    @DisplayName("Scenario 7: a service-hosted handler records with DomainEvents.record")
    class ServiceHostedHandler {

        @Test
        void recordedEventIsDispatchedAfterCommitAndTheSagaWaitsRunning() {
            seedInventory();
            SagaTestHarness harness = bookingHarness();

            UnitOfWorkResult result = harness.deliver(bookingRequested());

            PaymentInitiatedEvent initiated = new PaymentInitiatedEvent(PAYMENT, BOOKING, AMOUNT);
            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(paymentService.find(PAYMENT)).hasValueSatisfying(payment -> {
                assertThat(payment.bookingId()).isEqualTo(BOOKING);
                assertThat(payment.amount()).isEqualTo(AMOUNT);
            });
            assertThat(result.dispatched()).containsExactly(initiated);
            assertThat(harness.dispatcher().dispatched()).containsExactly(initiated);
            assertThat(harness.instance(result.sagaId()).status()).isEqualTo(SagaStatus.RUNNING);
        }

        @Test
        void recordedEventTriggersTheReactingStepInTheSameUnitOfWork() {
            SagaTestHarness harness = chargingHarness();

            UnitOfWorkResult result = harness.deliver(new ChargeRequested(BOOKING, AMOUNT));

            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(result.stepsRun()).containsExactly(ChargingSaga.START_STEP, ChargingSaga.END_STEP);
            assertThat(harness.eventLog().events(Kind.IN_PROCESS))
                    .containsExactly(new PaymentInitiatedEvent(PAYMENT, BOOKING, AMOUNT));
            assertThat(result.dispatched()).isEmpty();
            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(ChargingSaga.END_STEP);
        }
    }

    @Nested
    @DisplayName("Scenario 8: the event publisher outside a unit of work starts a saga")
    class PublisherStartsSaga {

        @Test
        void publishingTheStartEventStartsANewInstance() {
            seedInventory();
            SagaTestHarness harness = bookingHarness();
            BookingRequestedEvent start = bookingRequested();

            harness.eventPublisher().publish(start);

            assertThat(harness.eventPublisher().published()).containsExactly(start);
            assertThat(harness.eventPublisher().lastResults()).singleElement().satisfies(result -> {
                assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
                assertThat(result.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.FIRST_UNIT_STEPS);
            });
            assertThat(harness.instances(CreateBookingSaga.class)).singleElement().satisfies(instance -> {
                assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
                assertThat(instance.associations()).containsEntry("bookingId", BOOKING);
            });
            assertThat(harness.dispatcher().dispatched())
                    .containsExactly(start, new PaymentInitiatedEvent(PAYMENT, BOOKING, AMOUNT));
        }
    }

    @Nested
    @DisplayName("Scenario 9: a missing handler or a caught handler failure rolls the unit of work back")
    class HandlerFailureRollsBack {

        @Test
        void commandWithNoRegisteredHandlerRollsBackAndNamesTheCommandType() {
            seedInventory();
            // The builder does not reject a command type with no handler: it is a runtime failure (FR-011).
            SagaTestHarness harness = SagaTestHarness.builder()
                    .saga(CreateBookingSaga.class)
                    .aggregate(Inventory.class)
                    .repository(inventoryRepository)
                    .aggregate(Booking.class)
                    .repository(bookingRepository)
                    .build();

            UnitOfWorkResult result = harness.deliver(bookingRequested());

            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            assertThat(result.cause()).isInstanceOfSatisfying(NoCommandHandlerException.class, e -> {
                assertThat(e.getCommandType()).isEqualTo(InitiatePayment.class);
                assertThat(e.getMessage()).contains(InitiatePayment.class.getName());
            });
            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.failure().stepName()).isEqualTo(CreateBookingSaga.AVAILABILITY_CLAIMED_STEP);
            assertThat(instance.failure().causeType()).isEqualTo(NoCommandHandlerException.class.getName());
            // The claim made by the first step is rolled back (re-load: restore installs copies) ...
            assertThat(inventoryRepository.load(INVENTORY).claims()).isEmpty();
            // ... but saveCount is a call counter, not restored state: the first step's save still counts.
            assertThat(inventoryRepository.saveCount()).isEqualTo(1);
            assertThat(harness.dispatcher().dispatched()).isEmpty();
        }

        @Test
        void handlerFailureCaughtByTheStepStillRollsBack() {
            IllegalStateException injected = new IllegalStateException("payment provider unavailable");
            paymentService.failNext(injected);
            SagaTestHarness harness = chargingHarness();

            UnitOfWorkResult result = harness.deliver(new ChargeRequested(BOOKING, AMOUNT));

            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            assertThat(result.cause()).isSameAs(injected);
            assertThat(result.stepsRun()).containsExactly(ChargingSaga.START_STEP);
            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.failure().stepName()).isEqualTo(ChargingSaga.START_STEP);
            assertThat(instance.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(instance.steps().get(ChargingSaga.START_STEP).status()).isEqualTo(StepStatus.NOT_RUN);
            // The payment the handler created before failing is rolled back.
            assertThat(paymentService.find(PAYMENT)).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario 10: a creation handler's new aggregate is saved inside the unit of work")
    class CreationHandler {

        @Test
        void paymentConfirmedCreatesAndSavesTheBookingAndCompletesTheSaga() {
            seedInventory();
            SagaTestHarness harness = bookingHarness();
            String sagaId = harness.deliver(bookingRequested()).sagaId();
            assertThat(bookingRepository.saveCount()).isZero();

            UnitOfWorkResult result = harness.deliver(new PaymentConfirmedEvent(PAYMENT, BOOKING));

            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(result.sagaId()).isEqualTo(sagaId);
            assertThat(result.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.CONFIRMED_PATH_STEPS);
            assertThat(bookingRepository.saveCount()).isEqualTo(1);
            Booking booking = bookingRepository.load(BOOKING);
            assertThat(booking).isNotNull();
            assertThat(booking.claimId()).isEqualTo(CLAIM);
            assertThat(booking.paymentId()).isEqualTo(PAYMENT);
            SagaInstance instance = harness.instance(sagaId);
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
            assertThat(instance.steps().get(CreateBookingSaga.PAYMENT_DECLINED_STEP).status())
                    .isEqualTo(StepStatus.NOT_RUN);
        }
    }

    @Nested
    @DisplayName("Scenario 11: a missing target aggregate fails the unit of work")
    class MissingAggregate {

        @Test
        void unseededInventoryRollsBackWithAggregateNotFound() {
            SagaTestHarness harness = bookingHarness();

            UnitOfWorkResult result = harness.deliver(bookingRequested());

            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            assertThat(result.cause()).isInstanceOfSatisfying(AggregateNotFoundException.class, e -> {
                assertThat(e.getAggregateType()).isEqualTo(Inventory.class);
                assertThat(e.getAggregateId()).isEqualTo(INVENTORY);
            });
            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.failure().stepName()).isEqualTo(CreateBookingSaga.START_STEP);
            assertThat(inventoryRepository.saveCount()).isZero();
            assertThat(paymentService.changedAggregates()).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario 13: DomainEvents.record with no active unit of work fails immediately")
    class RecordOutsideUnitOfWork {

        @Test
        void recordFailsAndNothingIsDelivered() {
            SagaTestHarness harness = bookingHarness();

            assertThatThrownBy(() -> DomainEvents.record(bookingRequested()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(BookingRequestedEvent.class.getName());

            assertThat(harness.eventLog().entries()).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();
            assertThat(harness.instances(CreateBookingSaga.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-004: each completed step records the aggregates it touched")
    class TouchedAggregates {

        @Test
        void firstUnitOfWorkRecordsInventoryAndPayment() {
            seedInventory();
            SagaTestHarness harness = bookingHarness();

            SagaInstance instance = harness.instance(harness.deliver(bookingRequested()).sagaId());

            assertThat(instance.steps().get(CreateBookingSaga.START_STEP).touchedAggregates())
                    .containsExactly(AggregateReference.of(Inventory.class, INVENTORY));
            assertThat(instance.steps().get(CreateBookingSaga.AVAILABILITY_CLAIMED_STEP).touchedAggregates())
                    .containsExactly(AggregateReference.of(Payment.class, PAYMENT));
        }

        @Test
        void confirmationRecordsTheCreatedBooking() {
            seedInventory();
            SagaTestHarness harness = bookingHarness();
            String sagaId = harness.deliver(bookingRequested()).sagaId();

            harness.deliver(new PaymentConfirmedEvent(PAYMENT, BOOKING));

            SagaInstance instance = harness.instance(sagaId);
            assertThat(instance.steps().get(CreateBookingSaga.PAYMENT_CONFIRMED_STEP).touchedAggregates())
                    .containsExactly(AggregateReference.of(Booking.class, BOOKING));
            assertThat(instance.steps().get(CreateBookingSaga.BOOKING_CONFIRMED_STEP).status())
                    .isEqualTo(StepStatus.COMPLETED);
            assertThat(instance.steps().get(CreateBookingSaga.BOOKING_CONFIRMED_STEP).touchedAggregates())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Rollback restores the aggregates a handler changed")
    class RollbackRestoresAggregates {

        @Test
        void inventoryFailureLeavesNoClaim() {
            seedInventory();
            IllegalStateException injected = new IllegalStateException("inventory failure");
            inventoryRepository.load(INVENTORY).failNext(injected);
            SagaTestHarness harness = bookingHarness();

            UnitOfWorkResult result = harness.deliver(bookingRequested());

            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            assertThat(result.cause()).isSameAs(injected);
            assertThat(harness.instance(result.sagaId()).failure().stepName())
                    .isEqualTo(CreateBookingSaga.START_STEP);
            // Re-load: restore installs fresh copies, so the pre-rollback reference is stale.
            assertThat(inventoryRepository.load(INVENTORY).claims()).isEmpty();
            // The handler threw before returning, so the saver was never called.
            assertThat(inventoryRepository.saveCount()).isZero();
        }

        @Test
        void paymentFailureInTheSecondStepAlsoUndoesTheFirstStepsClaim() {
            seedInventory();
            paymentService.failNext();
            SagaTestHarness harness = bookingHarness();

            UnitOfWorkResult result = harness.deliver(bookingRequested());

            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            assertThat(harness.instance(result.sagaId()).failure().stepName())
                    .isEqualTo(CreateBookingSaga.AVAILABILITY_CLAIMED_STEP);
            assertThat(inventoryRepository.load(INVENTORY).claims()).isEmpty();
            assertThat(paymentService.find(PAYMENT)).isEmpty();
            // saveCount counts calls and is not restored by a rollback (fixture contract).
            assertThat(inventoryRepository.saveCount()).isEqualTo(1);
        }
    }
}
