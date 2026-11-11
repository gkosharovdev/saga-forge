package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ItemsReserved;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.DifferentialRun;
import io.github.gkosharovdev.sagaforge.runtime.support.DifferentialRun.Fixtures;
import io.github.gkosharovdev.sagaforge.runtime.support.DifferentialRun.Side;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The 002 User Story 1 happy-path scenarios of {@link ThreeStepSaga} end the same on the 002 {@code SagaTestHarness}
 * and on the engine (specs/004-saga-engine tasks.md T046, SC-001, SC-009, quickstart.md scenario 3).
 */
class ThreeStepSagaDifferentialTest {

    /** The three service handlers of one side, compared by the aggregates each changed. */
    private static final class ThreeStepFixtures implements Fixtures {

        private final ReservationService reservations = new ReservationService();
        private final InvoiceService invoices = new InvoiceService();
        private final ShipmentService shipments = new ShipmentService();

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

    @BeforeEach
    void resetProbe() {
        ThreeStepSagaProbe.reset();
    }

    private static DifferentialRun run(Object... events) {
        return DifferentialRun.of(ThreeStepSaga.class, ThreeStepFixtures::new).run(events);
    }

    private static void assertSameOutcome(DifferentialRun run) {
        Side harness = run.harness();
        Side engine = run.engine();

        assertThat(engine.failures()).isEmpty();
        assertThat(engine.instances()).isEqualTo(harness.instances());
        assertThat(engine.contexts()).isEqualTo(harness.contexts());
        assertThat(engine.dispatched()).isEqualTo(harness.dispatched());
        assertThat(engine.fixtureState()).isEqualTo(harness.fixtureState());
        assertThat(engine.comparable()).isEqualTo(harness.comparable());
    }

    @Test
    @DisplayName("one order: one COMPLETED instance, every step COMPLETED with the same touched aggregates")
    void oneOrder() {
        DifferentialRun run = run(new OrderPlaced("order-1", 250L, 3));

        assertSameOutcome(run);
        assertThat(run.engine().instances()).singleElement().satisfies(instance -> {
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(ThreeStepSaga.END_STEP);
        });
        assertThat(run.engine().contexts().values()).singleElement()
                .satisfies(values -> assertThat(values).containsEntry(ThreeStepSaga.CONTEXT_AMOUNT, 250L));
        assertThat(run.engine().dispatched()).isEmpty();
    }

    @Test
    @DisplayName("two orders: two independent COMPLETED instances, in creation order")
    void twoOrders() {
        DifferentialRun run = run(new OrderPlaced("order-1", 250L, 3), new OrderPlaced("order-2", 90L, 1));

        assertSameOutcome(run);
        assertThat(run.engine().instances()).hasSize(2)
                .allSatisfy(instance -> assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED));
    }

    @Test
    @DisplayName("an order placed again after its saga completed starts a second instance")
    void sameOrderAfterCompletion() {
        DifferentialRun run = run(new OrderPlaced("order-1", 250L, 3), new OrderPlaced("order-1", 250L, 3));

        assertSameOutcome(run);
        assertThat(run.engine().instances()).hasSize(2);
    }

    @Test
    @DisplayName("a later event for a completed instance, or for no instance, changes nothing on either side")
    void unexpectedAndUnmatchedEvents() {
        DifferentialRun run = run(
                new OrderPlaced("order-1", 250L, 3),
                new ItemsReserved(ReservationService.reservationIdFor("order-1"), "order-1", 3),
                new InvoiceIssued(InvoiceService.invoiceIdFor("order-2"), "order-2", 90L));

        assertSameOutcome(run);
        assertThat(run.engine().instances()).hasSize(1);
    }
}
