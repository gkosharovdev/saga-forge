package io.github.gkosharovdev.sagaforge.core.execution;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.AvailabilityClaimedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.AvailabilityReleasedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Payment;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentDeclinedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentInitiatedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.SagaTestHarness;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Spec 002 User Story 3, acceptance scenarios 1 to 6: {@link CreateBookingSaga} commits a first unit of work at
 * a wait point, resumes from persisted state in a fresh harness, and reaches exactly one of its two
 * {@code @EndSaga} outcomes (FR-003..FR-005, FR-018..FR-024, SC-003, SC-004).
 *
 * <p>{@link InventoryRepository}, {@link BookingRepository} and {@link PaymentService} keep their aggregates in
 * their own maps, like a client database outside the engine, so every resumed harness re-registers the same
 * objects while all saga-engine state comes from {@link SagaTestHarness#persistedState()} alone.</p>
 */
class LongRunningSagaTest {

    private static final String BOOKING = "b-42";
    private static final String OTHER_BOOKING = "b-unknown";
    private static final String INVENTORY = "inv-7";
    private static final long AMOUNT = 480L;
    private static final String CLAIM = Inventory.claimIdFor(BOOKING);
    private static final String PAYMENT = PaymentService.paymentIdFor(BOOKING);

    private PaymentService paymentService;
    private InventoryRepository inventoryRepository;
    private BookingRepository bookingRepository;

    @BeforeEach
    void setUp() {
        Booking.resetFaults();
        paymentService = new PaymentService();
        inventoryRepository = new InventoryRepository();
        inventoryRepository.add(new Inventory(INVENTORY));
        bookingRepository = new BookingRepository();
    }

    @Nested
    @DisplayName("Scenario 1: the first unit of work commits at the wait point")
    class WaitPointCommit {

        @Test
        void firstUnitOfWorkCommitsRunningWithTheFirstTwoStepsCompleted() {
            SagaTestHarness harness = newHarness();

            UnitOfWorkResult result = harness.deliver(bookingRequested(BOOKING));

            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(result.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.FIRST_UNIT_STEPS);
            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(instance.outcome()).isNull();
            assertThat(instance.failure()).isNull();
            assertStepStatuses(instance, CreateBookingSaga.FIRST_UNIT_STEPS);
            assertThat(instance.associations()).containsEntry("bookingId", BOOKING);

            // Availability claimed and payment initiated, both committed.
            assertThat(inventoryRepository.load(INVENTORY).claim(CLAIM)).isPresent();
            assertThat(paymentService.find(PAYMENT)).get()
                    .extracting(Payment::bookingId, Payment::amount)
                    .containsExactly(BOOKING, AMOUNT);
            assertThat(harness.context(result.sagaId()))
                    .containsEntry(CreateBookingSaga.CONTEXT_INVENTORY_ID, INVENTORY)
                    .containsEntry(CreateBookingSaga.CONTEXT_AMOUNT, AMOUNT)
                    .containsEntry(CreateBookingSaga.CONTEXT_CLAIM_ID, CLAIM)
                    .doesNotContainKey(CreateBookingSaga.CONTEXT_PAYMENT_ID);
        }

        @Test
        void paymentInitiatedIsDispatchedAfterCommitAndNotDeliveredInProcess() {
            SagaTestHarness harness = newHarness();

            UnitOfWorkResult result = harness.deliver(bookingRequested(BOOKING));

            PaymentInitiatedEvent initiated = new PaymentInitiatedEvent(PAYMENT, BOOKING, AMOUNT);
            assertThat(result.dispatched()).contains(initiated);
            assertThat(harness.dispatcher().dispatched()).contains(initiated);
            assertThat(harness.eventLog().events(Kind.DISPATCHED)).contains(initiated);
            assertThat(harness.eventLog().events(Kind.IN_PROCESS))
                    .contains(new AvailabilityClaimedEvent(INVENTORY, BOOKING, CLAIM))
                    .doesNotContain(initiated);
            // The dispatch follows the commit of the unit of work that recorded it.
            List<Kind> kinds = harness.eventLog().entries().stream().map(HarnessEventLog.Entry::kind).toList();
            assertThat(kinds.indexOf(Kind.COMMITTED)).isNotNegative().isLessThan(kinds.indexOf(Kind.DISPATCHED));
            assertThat(kinds).doesNotContain(Kind.ROLLED_BACK, Kind.DISCARDED);
        }
    }

    @Nested
    @DisplayName("Scenario 2: payment confirmed resumes in a fresh harness and completes")
    class ResumeConfirmed {

        @Test
        void freshHarnessCorrelatesTheEventAndReachesTheConfirmedOutcome() {
            SagaTestHarness original = newHarness();
            String sagaId = original.deliver(bookingRequested(BOOKING)).sagaId();
            SagaTestHarness resumed = resumedHarness(original);

            UnitOfWorkResult result = resumed.deliver(new PaymentConfirmedEvent(PAYMENT, BOOKING));

            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(result.sagaId()).isEqualTo(sagaId);
            assertThat(result.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.CONFIRMED_PATH_STEPS);
            SagaInstance instance = resumed.instance(sagaId);
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
            assertStepStatuses(instance, union(
                    CreateBookingSaga.FIRST_UNIT_STEPS, CreateBookingSaga.CONFIRMED_PATH_STEPS));
            assertThat(instance.steps().get(CreateBookingSaga.PAYMENT_DECLINED_STEP).status())
                    .isEqualTo(StepStatus.NOT_RUN);

            // ConfirmBooking was built from the claim id unit of work 1 left in SagaContext.
            Booking booking = bookingRepository.load(BOOKING);
            assertThat(booking).isNotNull();
            assertThat(booking.claimId()).isEqualTo(CLAIM);
            assertThat(booking.paymentId()).isEqualTo(PAYMENT);
            assertThat(inventoryRepository.load(INVENTORY).claim(CLAIM)).isPresent();
            assertThat(resumed.eventLog().events(Kind.IN_PROCESS))
                    .containsExactly(new BookingConfirmedEvent(BOOKING, CLAIM, PAYMENT));
            assertThat(original.instance(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
        }
    }

    @Nested
    @DisplayName("Scenario 3: payment declined resumes in a fresh harness and releases the claim")
    class ResumeDeclined {

        @Test
        void declinedOutcomeReadsTheClaimFromContextAndReleasesAvailability() {
            SagaTestHarness original = newHarness();
            String sagaId = original.deliver(bookingRequested(BOOKING)).sagaId();
            assertThat(inventoryRepository.load(INVENTORY).claim(CLAIM)).isPresent();
            SagaTestHarness resumed = resumedHarness(original);

            UnitOfWorkResult result = resumed.deliver(new PaymentDeclinedEvent(PAYMENT, BOOKING, "card declined"));

            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(result.sagaId()).isEqualTo(sagaId);
            assertThat(result.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.DECLINED_PATH_STEPS);
            SagaInstance instance = resumed.instance(sagaId);
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(CreateBookingSaga.OUTCOME_DECLINED);
            assertStepStatuses(instance, union(
                    CreateBookingSaga.FIRST_UNIT_STEPS, CreateBookingSaga.DECLINED_PATH_STEPS));
            assertThat(CreateBookingSaga.CONFIRMED_PATH_STEPS)
                    .allSatisfy(step -> assertThat(instance.steps().get(step).status()).isEqualTo(StepStatus.NOT_RUN));

            // ReleaseAvailability used the claim id read back from SagaContext.
            assertThat(inventoryRepository.load(INVENTORY).claims()).isEmpty();
            AvailabilityReleasedEvent released = new AvailabilityReleasedEvent(INVENTORY, BOOKING, CLAIM);
            assertThat(result.dispatched()).contains(released);
            assertThat(resumed.dispatcher().dispatched()).contains(released);
            assertThat(bookingRepository.load(BOOKING)).isNull();
            assertThat(bookingRepository.saveCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Scenario 4: a failing second unit of work keeps the first one's effects")
    class SecondUnitOfWorkFails {

        @Test
        void confirmBookingFailureRollsBackOnlyTheSecondUnitOfWork() {
            SagaTestHarness original = newHarness();
            String sagaId = original.deliver(bookingRequested(BOOKING)).sagaId();
            SagaTestHarness resumed = resumedHarness(original);
            IllegalStateException injected = new IllegalStateException("booking store unavailable");
            Booking.failNext(injected);

            UnitOfWorkResult result = resumed.deliver(new PaymentConfirmedEvent(PAYMENT, BOOKING));

            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            assertThat(result.sagaId()).isEqualTo(sagaId);
            assertThat(result.cause()).isSameAs(injected);
            assertThat(result.dispatched()).isEmpty();
            SagaInstance instance = resumed.instance(sagaId);
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.outcome()).isNull();
            assertThat(instance.failure().stepName()).isEqualTo(CreateBookingSaga.PAYMENT_CONFIRMED_STEP);
            assertThat(instance.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(instance.failure().message()).isEqualTo(injected.getMessage());
            // The first unit of work's step records survive; the failing step stays not run.
            assertStepStatuses(instance, CreateBookingSaga.FIRST_UNIT_STEPS);

            // The first unit of work's effects stay committed; the second one's are gone.
            assertThat(inventoryRepository.load(INVENTORY).claim(CLAIM)).isPresent();
            assertThat(paymentService.find(PAYMENT)).isPresent();
            assertThat(bookingRepository.load(BOOKING)).isNull();
            assertThat(bookingRepository.saveCount()).isZero();
            assertThat(resumed.eventLog().events(Kind.ROLLED_BACK))
                    .containsExactly(new PaymentConfirmedEvent(PAYMENT, BOOKING));
        }
    }

    @Nested
    @DisplayName("Scenario 5: context written in a rolled-back unit of work is still readable")
    class ContextSurvivesRollback {

        @Test
        void valueWrittenBeforeTheFailureIsStillInSagaContext() {
            SagaTestHarness original = newHarness();
            String sagaId = original.deliver(bookingRequested(BOOKING)).sagaId();
            SagaTestHarness resumed = resumedHarness(original);
            Booking.failNext();

            UnitOfWorkResult result = resumed.deliver(new PaymentConfirmedEvent(PAYMENT, BOOKING));

            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            Map<String, Object> context = resumed.context(sagaId);
            assertThat(context)
                    .containsEntry(CreateBookingSaga.CONTEXT_PAYMENT_ID, PAYMENT)
                    .containsEntry(CreateBookingSaga.CONTEXT_INVENTORY_ID, INVENTORY)
                    .containsEntry(CreateBookingSaga.CONTEXT_AMOUNT, AMOUNT)
                    .containsEntry(CreateBookingSaga.CONTEXT_CLAIM_ID, CLAIM);
            // ... and it is carried into persisted state like any other context value.
            SagaTestHarness later = resumedHarness(resumed);
            assertThat(later.context(sagaId)).isEqualTo(context);
        }
    }

    @Nested
    @DisplayName("Scenario 6: events for a completed saga or no instance have no effect")
    class UnexpectedAndUnmatched {

        @Test
        void eventCorrelatedToACompletedSagaIsUnexpectedAndHasNoEffect() {
            SagaTestHarness harness = newHarness();
            String sagaId = harness.deliver(bookingRequested(BOOKING)).sagaId();
            harness.deliver(new PaymentConfirmedEvent(PAYMENT, BOOKING));
            SagaInstance completed = harness.instance(sagaId);
            assertThat(completed.status()).isEqualTo(SagaStatus.COMPLETED);
            Map<String, Object> context = harness.context(sagaId);
            List<Inventory.Claim> claims = inventoryRepository.load(INVENTORY).claims();
            int inventorySaves = inventoryRepository.saveCount();
            int bookingSaves = bookingRepository.saveCount();
            int dispatches = harness.dispatcher().dispatched().size();

            PaymentDeclinedEvent late = new PaymentDeclinedEvent(PAYMENT, BOOKING, "late callback");
            UnitOfWorkResult result = harness.deliver(late);

            assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
            assertThat(result.sagaId()).isEqualTo(sagaId);
            assertThat(result.stepsRun()).isEmpty();
            assertThat(harness.instance(sagaId)).isEqualTo(completed);
            assertThat(harness.context(sagaId)).isEqualTo(context);
            assertThat(inventoryRepository.load(INVENTORY).claims()).isEqualTo(claims);
            assertThat(inventoryRepository.saveCount()).isEqualTo(inventorySaves);
            assertThat(bookingRepository.saveCount()).isEqualTo(bookingSaves);
            assertThat(harness.dispatcher().dispatched()).hasSize(dispatches);
            assertThat(harness.eventLog().unexpected()).containsExactly(late);
        }

        @Test
        void eventForAnUnknownBookingIsUnmatchedAndHasNoEffect() {
            SagaTestHarness harness = newHarness();
            String sagaId = harness.deliver(bookingRequested(BOOKING)).sagaId();
            SagaInstance waiting = harness.instance(sagaId);
            Map<String, Object> context = harness.context(sagaId);

            PaymentConfirmedEvent stray = new PaymentConfirmedEvent(
                    PaymentService.paymentIdFor(OTHER_BOOKING), OTHER_BOOKING);
            UnitOfWorkResult result = harness.deliver(stray);

            assertThat(result.outcome()).isEqualTo(Outcome.UNMATCHED);
            assertThat(result.sagaId()).isNull();
            assertThat(result.stepsRun()).isEmpty();
            assertThat(harness.instances(CreateBookingSaga.class)).containsExactly(waiting);
            assertThat(harness.context(sagaId)).isEqualTo(context);
            assertThat(bookingRepository.saveCount()).isZero();
            assertThat(harness.eventLog().unmatched()).containsExactly(stray);
        }
    }

    private SagaTestHarness newHarness() {
        return register(SagaTestHarness.builder());
    }

    /** A fresh harness built from {@code from}'s persisted state, with the same client objects re-registered. */
    private SagaTestHarness resumedHarness(SagaTestHarness from) {
        return register(SagaTestHarness.fromPersistedState(from.persistedState()));
    }

    private SagaTestHarness register(SagaTestHarness.Builder builder) {
        return builder
                .saga(CreateBookingSaga.class)
                .handler(paymentService)
                .aggregate(Inventory.class)
                .repository(inventoryRepository)
                .aggregate(Booking.class)
                .repository(bookingRepository)
                .build();
    }

    /** Asserts exactly {@code completed} are {@code COMPLETED} and every other step is {@code NOT_RUN}. */
    private static void assertStepStatuses(SagaInstance instance, List<String> completed) {
        assertThat(instance.steps()).containsOnlyKeys(CreateBookingSaga.ALL_STEPS);
        for (String step : CreateBookingSaga.ALL_STEPS) {
            StepStatus expected = completed.contains(step) ? StepStatus.COMPLETED : StepStatus.NOT_RUN;
            assertThat(instance.steps().get(step).status()).as("status of step %s", step).isEqualTo(expected);
        }
    }

    private static List<String> union(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    private static BookingRequestedEvent bookingRequested(String bookingId) {
        return new BookingRequestedEvent(
                bookingId, INVENTORY, LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 13), AMOUNT);
    }
}
