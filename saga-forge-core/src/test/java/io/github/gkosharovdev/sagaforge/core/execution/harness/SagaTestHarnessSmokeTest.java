package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ItemsReserved;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Reservation;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test of the {@link SagaTestHarness} API against {@link ThreeStepSaga}. */
class SagaTestHarnessSmokeTest {

    private static final String ORDER = "o-1";

    private ReservationService reservations;
    private InvoiceService invoices;
    private ShipmentService shipments;
    private SagaTestHarness harness;

    @BeforeEach
    void setUp() {
        ThreeStepSagaProbe.reset();
        reservations = new ReservationService();
        invoices = new InvoiceService();
        shipments = new ShipmentService();
        harness = SagaTestHarness.builder()
                .saga(ThreeStepSaga.class)
                .handler(reservations)
                .handler(invoices)
                .handler(shipments)
                .build();
    }

    @Test
    void happyPathCommitsEveryStepInOneUnitOfWork() {
        UnitOfWorkResult result = harness.deliver(new OrderPlaced(ORDER, 100L, 2));

        assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(result.stepsRun()).containsExactlyElementsOf(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
        assertThat(ThreeStepSagaProbe.enteredSteps()).containsExactlyElementsOf(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
        SagaInstance instance = harness.instance(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(instance.outcome()).isEqualTo(ThreeStepSaga.END_STEP);
        assertThat(instance.associations()).isEqualTo(Map.of("orderId", ORDER));
        assertThat(instance.steps().values()).allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.COMPLETED));
        assertThat(instance.steps().get(ThreeStepSaga.START_STEP).touchedAggregates())
                .containsExactly(AggregateReference.of(Reservation.class, ReservationService.reservationIdFor(ORDER)));
        assertThat(reservations.changedAggregates()).containsExactly(ReservationService.reservationIdFor(ORDER));
        assertThat(invoices.changedAggregates()).containsExactly(InvoiceService.invoiceIdFor(ORDER));
        assertThat(shipments.changedAggregates()).containsExactly(ShipmentService.shipmentIdFor(ORDER));
        assertThat(harness.context(result.sagaId())).containsEntry(ThreeStepSaga.CONTEXT_AMOUNT, 100L);
        assertThat(result.dispatched()).isEmpty();
        assertThat(harness.eventLog().events(Kind.IN_PROCESS)).hasSize(3);
        assertThat(harness.eventLog().entries(Kind.COMMITTED)).hasSize(1);
    }

    @Test
    void handlerFailureRollsBackEveryChangeAndLeavesTheInstanceFailed() {
        invoices.failNext();

        UnitOfWorkResult result = harness.deliver(new OrderPlaced(ORDER, 100L, 2));

        assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
        SagaInstance instance = harness.instance(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(instance.failure().stepName()).isEqualTo(ThreeStepSaga.RESERVED_STEP);
        assertThat(instance.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(instance.steps().values()).allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.NOT_RUN));
        assertThat(reservations.changedAggregates()).isEmpty();
        assertThat(invoices.changedAggregates()).isEmpty();
        assertThat(shipments.changedAggregates()).isEmpty();
        assertThat(harness.context(result.sagaId())).containsEntry(ThreeStepSaga.CONTEXT_AMOUNT, 100L);
        assertThat(harness.dispatcher().dispatched()).isEmpty();
    }

    @Test
    void nonStartEventWithoutInstanceIsUnmatchedAndAfterCompletionUnexpected() {
        ItemsReserved stray = new ItemsReserved(ReservationService.reservationIdFor(ORDER), ORDER, 2);

        assertThat(harness.deliver(stray).outcome()).isEqualTo(Outcome.UNMATCHED);

        harness.deliver(new OrderPlaced(ORDER, 100L, 2));
        assertThat(harness.deliver(stray).outcome()).isEqualTo(Outcome.UNEXPECTED);
        assertThat(harness.eventLog().unmatched()).containsExactly(stray);
        assertThat(harness.eventLog().unexpected()).containsExactly(stray);
    }
}
