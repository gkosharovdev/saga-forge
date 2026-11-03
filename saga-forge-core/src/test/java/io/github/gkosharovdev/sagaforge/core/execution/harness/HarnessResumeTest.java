package io.github.gkosharovdev.sagaforge.core.execution.harness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentDeclinedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * {@link SagaTestHarness#persistedState()} / {@link SagaTestHarness#fromPersistedState(PersistedState)},
 * non-start step resolution and one unit of work per instance at a time, against {@link CreateBookingSaga}
 * (spec FR-003, FR-020, FR-022, SC-003; contracts/saga-unit-of-work.md "Invariants").
 *
 * <p>The booking fixtures' {@link InventoryRepository}, {@link BookingRepository} and {@link PaymentService} hold
 * their aggregates in their own maps, outside the harness stores, like a client database outside the engine. A
 * resumed harness therefore gets the same objects registered again: the "database" survives the restart, while
 * every saga-engine store comes only from the {@link PersistedState}.</p>
 */
class HarnessResumeTest {

    private static final String BOOKING = "b-1";
    private static final String INVENTORY = "inv-1";
    private static final long AMOUNT = 250L;
    private static final String PAYMENT = PaymentService.paymentIdFor(BOOKING);
    private static final String CLAIM = Inventory.claimIdFor(BOOKING);

    private PaymentService payments;
    private InventoryRepository inventories;
    private BookingRepository bookings;

    @BeforeEach
    void setUp() {
        Booking.resetFaults();
        payments = new PaymentService();
        inventories = new InventoryRepository();
        inventories.add(new Inventory(INVENTORY));
        bookings = new BookingRepository();
    }

    @Test
    void freshHarnessResumesWaitingSagaFromPersistedStateAndReachesConfirmedOutcome() {
        SagaTestHarness original = bookingHarness(SagaTestHarness.builder(), inventories);
        UnitOfWorkResult first = original.deliver(bookingRequested());
        String sagaId = first.sagaId();
        assertThat(first.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(first.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.FIRST_UNIT_STEPS);
        assertThat(original.instance(sagaId).status()).isEqualTo(SagaStatus.RUNNING);

        PersistedState state = original.persistedState();
        assertThat(state.instances()).extracting(SagaInstance::sagaId).containsExactly(sagaId);
        assertThat(state.contextRecords()).extracting(SagaContextRecord::sagaId).containsExactly(sagaId);

        SagaTestHarness resumed = bookingHarness(SagaTestHarness.fromPersistedState(state), inventories);
        UnitOfWorkResult second = resumed.deliver(paymentConfirmed());

        assertThat(second.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(second.sagaId()).isEqualTo(sagaId);
        assertThat(second.stepsRun()).containsExactlyElementsOf(CreateBookingSaga.CONFIRMED_PATH_STEPS);
        SagaInstance done = resumed.instance(sagaId);
        assertThat(done.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(done.outcome()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
        assertThat(done.steps().get(CreateBookingSaga.PAYMENT_DECLINED_STEP).status()).isEqualTo(StepStatus.NOT_RUN);
        // The claim id written into SagaContext by unit of work 1 was read by unit of work 2 to build ConfirmBooking.
        Booking booking = bookings.load(BOOKING);
        assertThat(booking.claimId()).isEqualTo(CLAIM);
        assertThat(booking.paymentId()).isEqualTo(PAYMENT);
        assertThat(resumed.context(sagaId))
                .containsEntry(CreateBookingSaga.CONTEXT_INVENTORY_ID, INVENTORY)
                .containsEntry(CreateBookingSaga.CONTEXT_AMOUNT, AMOUNT)
                .containsEntry(CreateBookingSaga.CONTEXT_CLAIM_ID, CLAIM)
                .containsEntry(CreateBookingSaga.CONTEXT_PAYMENT_ID, PAYMENT);
        assertThat(resumed.eventLog().events(Kind.DELIVERED)).containsExactly(paymentConfirmed());

        // The original harness' stores are unaffected by the resumed run.
        assertThat(original.instance(sagaId)).isEqualTo(state.instances().get(0));
        assertThat(original.instance(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(original.context(sagaId)).doesNotContainKey(CreateBookingSaga.CONTEXT_PAYMENT_ID);
        assertThat(original.storage().all()).isEqualTo(state.instances());
        assertThat(original.contextStore().all()).isEqualTo(state.contextRecords());
        assertThat(original.eventLog().events(Kind.DELIVERED)).containsExactly(bookingRequested());
        // ... and so is the captured state, which can seed another harness.
        assertThat(state.newStorage().findById(sagaId).orElseThrow().status()).isEqualTo(SagaStatus.RUNNING);
    }

    @Test
    void resumedRunEndsLikeAnUninterruptedRun() {
        SagaTestHarness uninterrupted = bookingHarness(SagaTestHarness.builder(), inventories);
        String sagaId = uninterrupted.deliver(bookingRequested()).sagaId();
        uninterrupted.deliver(paymentConfirmed());

        InventoryRepository otherInventories = new InventoryRepository();
        otherInventories.add(new Inventory(INVENTORY));
        BookingRepository otherBookings = new BookingRepository();
        SagaTestHarness original = bookingHarness(SagaTestHarness.builder(), otherInventories, otherBookings);
        String resumedId = original.deliver(bookingRequested()).sagaId();
        SagaTestHarness resumed = bookingHarness(
                SagaTestHarness.fromPersistedState(original.persistedState()), otherInventories, otherBookings);
        resumed.deliver(paymentConfirmed());

        SagaInstance expected = uninterrupted.instance(sagaId);
        SagaInstance actual = resumed.instance(resumedId);
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.outcome()).isEqualTo(expected.outcome());
        assertThat(actual.steps()).isEqualTo(expected.steps());
        assertThat(resumed.context(resumedId)).isEqualTo(uninterrupted.context(sagaId));
    }

    @Test
    void aggregateStoreIsCopiedWithItsCopiersAndSharesNoMemory() {
        SagaTestHarness original = bookingHarness(SagaTestHarness.builder(), inventories);
        original.aggregates().registerCopier(Inventory.class, Inventory::copy);
        Inventory stored = new Inventory("inv-store");
        original.aggregates().put(Inventory.class, stored.inventoryId(), stored);

        PersistedState state = original.persistedState();
        SagaTestHarness resumed = bookingHarness(SagaTestHarness.fromPersistedState(state), inventories);

        Inventory copy = resumed.aggregates().get(Inventory.class, "inv-store").orElseThrow();
        assertThat(copy).isNotSameAs(stored);
        assertThat(copy.inventoryId()).isEqualTo(stored.inventoryId());
        assertThat(state.aggregates().get(Inventory.class, "inv-store").orElseThrow())
                .isNotSameAs(stored)
                .isNotSameAs(copy);
        resumed.aggregates().remove(Inventory.class, "inv-store");
        assertThat(original.aggregates().get(Inventory.class, "inv-store")).containsSame(stored);
        assertThat(state.newAggregateStore().get(Inventory.class, "inv-store")).isPresent();
    }

    @Test
    void unmatchedAndUnexpectedEventsAreLoggedWithNoEffect() {
        SagaTestHarness harness = bookingHarness(SagaTestHarness.builder(), inventories);

        UnitOfWorkResult unmatched = harness.deliver(paymentConfirmed());
        assertThat(unmatched.outcome()).isEqualTo(Outcome.UNMATCHED);
        assertThat(unmatched.sagaId()).isNull();
        assertThat(harness.storage().all()).isEmpty();
        assertThat(harness.contextStore().all()).isEmpty();
        assertThat(payments.changedAggregates()).isEmpty();
        assertThat(bookings.saveCount()).isZero();

        String sagaId = harness.deliver(bookingRequested()).sagaId();
        harness.deliver(paymentConfirmed());
        SagaInstance completed = harness.instance(sagaId);
        Map<String, Object> context = harness.context(sagaId);
        List<Inventory.Claim> claims = inventories.load(INVENTORY).claims();
        int inventorySaves = inventories.saveCount();

        PaymentDeclinedEvent late = new PaymentDeclinedEvent(PAYMENT, BOOKING, "too late");
        UnitOfWorkResult unexpected = harness.deliver(late);

        assertThat(unexpected.outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(unexpected.sagaId()).isEqualTo(sagaId);
        assertThat(unexpected.stepsRun()).isEmpty();
        assertThat(harness.instance(sagaId)).isEqualTo(completed);
        assertThat(harness.context(sagaId)).isEqualTo(context);
        assertThat(inventories.load(INVENTORY).claims()).isEqualTo(claims);
        assertThat(inventories.saveCount()).isEqualTo(inventorySaves);
        assertThat(harness.eventLog().unmatched()).containsExactly(paymentConfirmed());
        assertThat(harness.eventLog().unexpected()).containsExactly(late);
        assertThat(harness.eventLog().entries(Kind.UNEXPECTED).get(0).sagaId()).isEqualTo(sagaId);
    }

    @Test
    void deliverAndPersistedStateAreRejectedWhileAUnitOfWorkRuns() {
        List<RuntimeException> rejections = new ArrayList<>();
        SagaTestHarness[] self = new SagaTestHarness[1];
        InventoryRepository reentrant = new InventoryRepository() {
            @Override
            public Inventory load(String inventoryId) {
                try {
                    self[0].deliver(paymentConfirmed());
                } catch (IllegalStateException e) {
                    rejections.add(e);
                }
                try {
                    self[0].persistedState();
                } catch (IllegalStateException e) {
                    rejections.add(e);
                }
                return super.load(inventoryId);
            }
        };
        reentrant.add(new Inventory(INVENTORY));
        self[0] = bookingHarness(SagaTestHarness.builder(), reentrant);

        UnitOfWorkResult result = self[0].deliver(bookingRequested());

        assertThat(rejections).hasSize(2).allSatisfy(e -> assertThat(e).isInstanceOf(IllegalStateException.class));
        assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(self[0].eventLog().events(Kind.DELIVERED)).containsExactly(bookingRequested());
        assertThat(self[0].eventLog().unmatched()).isEmpty();
    }

    @Test
    void fromPersistedStateRejectsNull() {
        assertThatThrownBy(() -> SagaTestHarness.fromPersistedState(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SagaTestHarness bookingHarness(SagaTestHarness.Builder builder, InventoryRepository inventoryRepository) {
        return bookingHarness(builder, inventoryRepository, bookings);
    }

    private SagaTestHarness bookingHarness(
            SagaTestHarness.Builder builder, InventoryRepository inventoryRepository,
            BookingRepository bookingRepository) {
        return builder
                .saga(CreateBookingSaga.class)
                .handler(payments)
                .aggregate(Inventory.class)
                .repository(inventoryRepository)
                .aggregate(Booking.class)
                .repository(bookingRepository)
                .build();
    }

    private static BookingRequestedEvent bookingRequested() {
        return new BookingRequestedEvent(
                BOOKING, INVENTORY, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4), AMOUNT);
    }

    private static PaymentConfirmedEvent paymentConfirmed() {
        return new PaymentConfirmedEvent(PAYMENT, BOOKING);
    }
}
