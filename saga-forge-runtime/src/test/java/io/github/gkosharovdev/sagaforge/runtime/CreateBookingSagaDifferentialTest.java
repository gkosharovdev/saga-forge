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
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.DifferentialRun;
import io.github.gkosharovdev.sagaforge.runtime.support.DifferentialRun.Fixtures;
import io.github.gkosharovdev.sagaforge.runtime.support.DifferentialRun.InstanceView;
import io.github.gkosharovdev.sagaforge.runtime.support.DifferentialRun.Side;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Both outcomes of {@link CreateBookingSaga}, run straight through and resumed after a restart, end the same on the
 * 002 {@code SagaTestHarness} and on the engine (specs/004-saga-engine tasks.md T067, the 002 User Story 2 and 3
 * scenarios, SC-001, SC-009).
 */
class CreateBookingSagaDifferentialTest {

    private static final String BOOKING = "booking-1";
    private static final String INVENTORY = "inventory-1";

    private static final BookingRequestedEvent REQUESTED = new BookingRequestedEvent(BOOKING, INVENTORY,
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4), 300L);
    private static final PaymentConfirmedEvent CONFIRMED =
            new PaymentConfirmedEvent(PaymentService.paymentIdFor(BOOKING), BOOKING);
    private static final PaymentDeclinedEvent DECLINED =
            new PaymentDeclinedEvent(PaymentService.paymentIdFor(BOOKING), BOOKING, "card expired");

    /** The booking handlers and repositories of one side, with one seeded inventory. */
    private static final class BookingFixtures implements Fixtures {

        private final PaymentService payments = new PaymentService();
        private final InventoryRepository inventories = new InventoryRepository();
        private final BookingRepository bookings = new BookingRepository();

        BookingFixtures() {
            inventories.add(new Inventory(INVENTORY));
        }

        @Override
        public List<Object> handlers() {
            return List.of(payments);
        }

        @Override
        public List<Class<?>> aggregates() {
            return List.of(Inventory.class, Booking.class);
        }

        @Override
        public List<Object> repositories() {
            return List.of(inventories, bookings);
        }

        @Override
        public Object state() {
            Booking booking = bookings.load(BOOKING);
            return List.of(payments.changedAggregates(), inventories.load(INVENTORY).claims(),
                    booking == null ? "no booking" : List.of(booking.claimId(), booking.paymentId()));
        }
    }

    @BeforeEach
    void resetFaults() {
        Booking.resetFaults();
    }

    private static DifferentialRun newRun() {
        return DifferentialRun.of(CreateBookingSaga.class, BookingFixtures::new);
    }

    private static Side assertSameOutcome(DifferentialRun run) {
        Side harness = run.harness();
        Side engine = run.engine();

        assertThat(engine.failures()).isEmpty();
        assertThat(engine.instances()).isEqualTo(harness.instances());
        assertThat(engine.contexts()).isEqualTo(harness.contexts());
        assertThat(engine.dispatched()).isEqualTo(harness.dispatched());
        assertThat(engine.fixtureState()).isEqualTo(harness.fixtureState());
        return engine;
    }

    private static InstanceView onlyInstance(Side side) {
        assertThat(side.instances()).hasSize(1);
        return side.instances().get(0);
    }

    @Test
    @DisplayName("first unit of work: RUNNING with the same steps, context and dispatched PaymentInitiatedEvent")
    void firstUnitOfWork() {
        Side engine = assertSameOutcome(newRun().run(REQUESTED));

        assertThat(onlyInstance(engine).status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(engine.dispatched()).hasSize(1);
    }

    @Test
    @DisplayName("payment confirmed: COMPLETED with outcome onBookingConfirmed")
    void paymentConfirmed() {
        Side engine = assertSameOutcome(newRun().run(REQUESTED, CONFIRMED));

        assertThat(onlyInstance(engine).outcome()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
    }

    @Test
    @DisplayName("payment declined: COMPLETED with outcome onPaymentDeclined, the claim released")
    void paymentDeclined() {
        Side engine = assertSameOutcome(newRun().run(REQUESTED, DECLINED));

        assertThat(onlyInstance(engine).outcome()).isEqualTo(CreateBookingSaga.OUTCOME_DECLINED);
    }

    @Test
    @DisplayName("payment confirmed after a restart between the units of work")
    void paymentConfirmedAfterRestart() {
        Side engine = assertSameOutcome(newRun().run(REQUESTED).restart().run(CONFIRMED));

        assertThat(onlyInstance(engine).outcome()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
    }

    @Test
    @DisplayName("payment declined after a restart between the units of work")
    void paymentDeclinedAfterRestart() {
        Side engine = assertSameOutcome(newRun().run(REQUESTED).restart().run(DECLINED));

        assertThat(onlyInstance(engine).outcome()).isEqualTo(CreateBookingSaga.OUTCOME_DECLINED);
    }

    @Test
    @DisplayName("a restarted run ends exactly as a straight-through run")
    void restartChangesNothing() {
        assertThat(newRun().run(REQUESTED).restart().run(CONFIRMED).engine().comparable())
                .isEqualTo(newRun().run(REQUESTED, CONFIRMED).engine().comparable());
    }
}
