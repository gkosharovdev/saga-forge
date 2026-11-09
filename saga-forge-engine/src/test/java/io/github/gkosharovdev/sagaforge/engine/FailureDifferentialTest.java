package io.github.gkosharovdev.sagaforge.engine;

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
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.support.DifferentialRun;
import io.github.gkosharovdev.sagaforge.engine.support.DifferentialRun.Fixtures;
import io.github.gkosharovdev.sagaforge.engine.support.DifferentialRun.InstanceView;
import io.github.gkosharovdev.sagaforge.engine.support.DifferentialRun.Side;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The 002 failure scenarios end the same on the {@code SagaTestHarness} and on the engine: status, step records,
 * failure step and cause type, aggregate state, context values and dispatched events (specs/004-saga-engine tasks.md
 * T058, SC-001, SC-009).
 */
class FailureDifferentialTest {

    private static final String BOOKING = "booking-1";
    private static final String INVENTORY = "inventory-1";

    /** Which {@code ThreeStepSaga} handler fails. */
    enum ThreeStepFault { RESERVATION, INVOICE, SHIPMENT }

    /** Which handler of {@code CreateBookingSaga}'s first unit of work fails. */
    enum BookingFault { INVENTORY, PAYMENT }

    /** The three service handlers of one side, one of them armed to fail. */
    private static final class ThreeStepFixtures implements Fixtures {

        private final ReservationService reservations = new ReservationService();
        private final InvoiceService invoices = new InvoiceService();
        private final ShipmentService shipments = new ShipmentService();

        ThreeStepFixtures(ThreeStepFault fault) {
            switch (fault) {
                case RESERVATION -> reservations.failNext();
                case INVOICE -> invoices.failNext();
                case SHIPMENT -> shipments.failNext();
            }
        }

        @Override
        public List<Object> handlers() {
            return List.of(reservations, invoices, shipments);
        }

        @Override
        public Object state() {
            return List.of(reservations.changedAggregates(), invoices.changedAggregates(),
                    shipments.changedAggregates());
        }
    }

    /** The booking handlers and repositories of one side, with a seeded inventory and one handler armed. */
    private static final class BookingFixtures implements Fixtures {

        private final PaymentService payments = new PaymentService();
        private final InventoryRepository inventories = new InventoryRepository();
        private final BookingRepository bookings = new BookingRepository();

        BookingFixtures(BookingFault fault) {
            Inventory inventory = new Inventory(INVENTORY);
            inventories.add(inventory);
            switch (fault) {
                case INVENTORY -> inventory.failNext();
                case PAYMENT -> payments.failNext();
            }
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
            return List.of(payments.changedAggregates(), inventories.load(INVENTORY).claims());
        }
    }

    @BeforeEach
    void resetFixtures() {
        ThreeStepSagaProbe.reset();
        Booking.resetFaults();
    }

    private static void assertSameFailure(DifferentialRun run) {
        Side harness = run.harness();
        Side engine = run.engine();

        assertThat(engine.failures()).isEmpty();
        assertThat(engine.instances()).singleElement().satisfies(instance -> {
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.failure()).isNotNull();
        });
        // Step and cause first, for a readable message; then the whole instance, failure included.
        assertThat(harness.instances()).hasSize(1);
        InstanceView engineInstance = engine.instances().get(0);
        InstanceView harnessInstance = harness.instances().get(0);
        assertThat(engineInstance.failure().stepName()).isEqualTo(harnessInstance.failure().stepName());
        assertThat(engineInstance.failure().causeType()).isEqualTo(harnessInstance.failure().causeType());
        assertThat(engine.instances()).isEqualTo(harness.instances());
        assertThat(engine.contexts()).isEqualTo(harness.contexts());
        assertThat(engine.dispatched()).isEqualTo(harness.dispatched()).isEmpty();
        assertThat(engine.fixtureState()).isEqualTo(harness.fixtureState());
    }

    @ParameterizedTest(name = "{0} handler fails")
    @EnumSource(ThreeStepFault.class)
    @DisplayName("ThreeStepSaga: a failing handler fails the saga the same way on both sides")
    void threeStepSagaFailure(ThreeStepFault fault) {
        DifferentialRun run = DifferentialRun.of(ThreeStepSaga.class, () -> new ThreeStepFixtures(fault))
                .run(new OrderPlaced("order-1", 250L, 3));

        assertSameFailure(run);
    }

    @ParameterizedTest(name = "{0} handler fails")
    @EnumSource(BookingFault.class)
    @DisplayName("CreateBookingSaga: a failing handler in the first unit of work fails the saga the same way")
    void bookingFirstUnitFailure(BookingFault fault) {
        DifferentialRun run = DifferentialRun.of(CreateBookingSaga.class, () -> new BookingFixtures(fault))
                .run(new BookingRequestedEvent(BOOKING, INVENTORY, LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 3, 4), 300L));

        assertSameFailure(run);
    }
}
