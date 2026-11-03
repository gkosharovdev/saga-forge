package io.github.gkosharovdev.sagaforge.core.execution;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Invoice;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ItemsReserved;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Reservation;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Shipment;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentScheduled;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Entry;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.SagaTestHarness;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Spec 002 User Story 1, acceptance scenarios 1 to 5: event-triggered steps run in one saga-scoped unit of
 * work, exercised against {@link ThreeStepSaga} through the test-only {@link SagaTestHarness} (the reference
 * model of contracts/saga-unit-of-work.md).
 *
 * <p>Covers FR-001 (event order), FR-003 (unmatched / unexpected), FR-004/FR-005 (step and instance status),
 * FR-017/FR-019 (in-process delivery, nothing passes through the transport between steps), FR-020 (commit at
 * {@code @EndSaga}), FR-021 (rollback leaves {@code FAILED} with step and cause) and FR-024 (SagaContext
 * survives a rollback).</p>
 */
class SagaUnitOfWorkTest {

    private static final String ORDER = "order-1";
    private static final long AMOUNT = 250L;
    private static final int QUANTITY = 3;

    private static final AggregateReference RESERVATION =
            AggregateReference.of(Reservation.class, ReservationService.reservationIdFor(ORDER));
    private static final AggregateReference INVOICE =
            AggregateReference.of(Invoice.class, InvoiceService.invoiceIdFor(ORDER));
    private static final AggregateReference SHIPMENT =
            AggregateReference.of(Shipment.class, ShipmentService.shipmentIdFor(ORDER));

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

    private UnitOfWorkResult placeOrder() {
        return harness.deliver(new OrderPlaced(ORDER, AMOUNT, QUANTITY));
    }

    private void assertNoAggregateChangesRemain() {
        assertThat(reservations.changedAggregates()).isEmpty();
        assertThat(invoices.changedAggregates()).isEmpty();
        assertThat(shipments.changedAggregates()).isEmpty();
    }

    private void assertEveryStepNotRun(SagaInstance instance) {
        assertThat(instance.steps()).containsOnlyKeys(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
        assertThat(instance.steps().values()).allSatisfy(step -> {
            assertThat(step.status()).isEqualTo(StepStatus.NOT_RUN);
            assertThat(step.touchedAggregates()).isEmpty();
        });
    }

    private static List<Class<?>> eventTypes(List<?> events) {
        return events.stream().<Class<?>>map(Object::getClass).toList();
    }

    @Nested
    @DisplayName("Scenario 1: every step commits together in one unit of work")
    class HappyPath {

        @Test
        @DisplayName("the unit of work commits and the saga is COMPLETED with its @EndSaga step as the outcome")
        void commitsAndCompletesWithOutcome() {
            UnitOfWorkResult result = placeOrder();

            assertThat(result.committed()).isTrue();
            assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
            assertThat(result.cause()).isNull();
            assertThat(result.failure()).isNull();
            assertThat(result.stepsRun()).containsExactlyElementsOf(ThreeStepSaga.STEPS_IN_EVENT_ORDER);

            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(ThreeStepSaga.END_STEP);
            assertThat(instance.failure()).isNull();
            assertThat(instance.associations()).isEqualTo(Map.of("orderId", ORDER));
            assertThat(harness.instances(ThreeStepSaga.class)).hasSize(1);
        }

        @Test
        @DisplayName("every step is COMPLETED with the aggregates of the events recorded while it ran")
        void everyStepCompletedWithTouchedAggregates() {
            SagaInstance instance = harness.instance(placeOrder().sagaId());

            Map<String, StepRecord> steps = instance.steps();
            assertThat(steps).containsOnlyKeys(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
            assertThat(steps.values()).allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.COMPLETED));
            // "Touched" = (DomainEvent.aggregate, @AggregateIdentifier) of every event recorded while the step
            // ran (FR-004): the start step's ReserveItems handler recorded ItemsReserved, and so on.
            assertThat(steps.get(ThreeStepSaga.START_STEP).touchedAggregates()).containsExactly(RESERVATION);
            assertThat(steps.get(ThreeStepSaga.RESERVED_STEP).touchedAggregates()).containsExactly(INVOICE);
            assertThat(steps.get(ThreeStepSaga.INVOICED_STEP).touchedAggregates()).containsExactly(SHIPMENT);
            // The @EndSaga step dispatches nothing, so no event is recorded while it runs.
            assertThat(steps.get(ThreeStepSaga.END_STEP).touchedAggregates()).isEmpty();
        }

        @Test
        @DisplayName("each handler changed exactly its own aggregate")
        void eachHandlerChangedExactlyItsAggregate() {
            placeOrder();

            assertThat(reservations.changedAggregates()).containsExactly(ReservationService.reservationIdFor(ORDER));
            assertThat(invoices.changedAggregates()).containsExactly(InvoiceService.invoiceIdFor(ORDER));
            assertThat(shipments.changedAggregates()).containsExactly(ShipmentService.shipmentIdFor(ORDER));
        }

        @Test
        @DisplayName("events between steps are delivered in-process; nothing reaches the transport")
        void nothingObservableBetweenSteps() {
            UnitOfWorkResult result = placeOrder();

            List<Entry> inProcess = harness.eventLog().entries(Kind.IN_PROCESS);
            assertThat(eventTypes(inProcess.stream().map(Entry::event).toList()))
                    .containsExactly(ItemsReserved.class, InvoiceIssued.class, ShipmentScheduled.class);
            assertThat(inProcess).extracting(Entry::stepName)
                    .containsExactly(ThreeStepSaga.RESERVED_STEP, ThreeStepSaga.INVOICED_STEP, ThreeStepSaga.END_STEP);
            assertThat(inProcess).allSatisfy(entry -> assertThat(entry.sagaId()).isEqualTo(result.sagaId()));

            assertThat(harness.eventLog().entries(Kind.DISPATCHED)).isEmpty();
            assertThat(harness.eventLog().entries(Kind.DISCARDED)).isEmpty();
            assertThat(result.dispatched()).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();

            assertThat(harness.eventLog().entries(Kind.DELIVERED)).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.event()).isInstanceOf(OrderPlaced.class);
                        assertThat(entry.stepName()).isEqualTo(ThreeStepSaga.START_STEP);
                    });
            assertThat(harness.eventLog().entries(Kind.COMMITTED)).hasSize(1);
            assertThat(harness.eventLog().entries(Kind.ROLLED_BACK)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario 2: the command handler invoked by the second step fails")
    class HandlerFailureAtSecondStep {

        @Test
        @DisplayName("the unit of work rolls back and no aggregate change remains")
        void rollsBackEveryAggregateChange() {
            invoices.failNext();

            UnitOfWorkResult result = placeOrder();

            assertThat(result.rolledBack()).isTrue();
            assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
            assertThat(result.stepsRun()).containsExactly(ThreeStepSaga.START_STEP, ThreeStepSaga.RESERVED_STEP);
            assertNoAggregateChangesRemain();
        }

        @Test
        @DisplayName("the saga is FAILED with the failing step and the cause, and every step stays NOT_RUN")
        void recordsFailureWithStepAndCause() {
            invoices.failNext();

            UnitOfWorkResult result = placeOrder();

            assertThat(result.cause()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("InvoiceService failure injected");
            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.outcome()).isNull();
            assertThat(instance.failure()).isEqualTo(result.failure());
            assertThat(instance.failure().stepName()).isEqualTo(ThreeStepSaga.RESERVED_STEP);
            assertThat(instance.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(instance.failure().message()).isEqualTo("InvoiceService failure injected");
            assertEveryStepNotRun(instance);
        }

        @Test
        @DisplayName("nothing is dispatched")
        void nothingDispatched() {
            invoices.failNext();

            UnitOfWorkResult result = placeOrder();

            assertThat(result.dispatched()).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();
            assertThat(harness.eventLog().entries(Kind.DISPATCHED)).isEmpty();
            // ItemsReserved was delivered in-process before the failure, and the failing InvoiceService throws
            // before recording InvoiceIssued, so no recorded event is left to discard.
            assertThat(eventTypes(harness.eventLog().events(Kind.IN_PROCESS))).containsExactly(ItemsReserved.class);
            assertThat(harness.eventLog().entries(Kind.DISCARDED)).isEmpty();
            assertThat(harness.eventLog().entries(Kind.ROLLED_BACK)).singleElement()
                    .extracting(Entry::stepName).isEqualTo(ThreeStepSaga.RESERVED_STEP);
        }

        @Test
        @DisplayName("an event recorded but not yet delivered in-process is discarded by the rollback")
        void undeliveredRecordedEventIsDiscarded() {
            // The third step fails after its ScheduleShipment handler recorded ShipmentScheduled, which is
            // therefore still queued (not delivered in-process) when the unit of work rolls back.
            ThreeStepSagaProbe.failStep(ThreeStepSaga.INVOICED_STEP);

            UnitOfWorkResult result = placeOrder();

            assertThat(result.rolledBack()).isTrue();
            assertThat(eventTypes(harness.eventLog().events(Kind.DISCARDED))).containsExactly(ShipmentScheduled.class);
            assertThat(harness.eventLog().entries(Kind.DISPATCHED)).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();
            assertNoAggregateChangesRemain();
            assertThat(harness.instance(result.sagaId()).failure().stepName()).isEqualTo(ThreeStepSaga.INVOICED_STEP);
        }
    }

    @Nested
    @DisplayName("Scenario 3: the @EndSaga step fails")
    class EndStepFailure {

        @Test
        @DisplayName("all three aggregate changes roll back and the saga is FAILED at the @EndSaga step")
        void rollsBackEverythingAndFailsAtEndStep() {
            ThreeStepSagaProbe.failStep(ThreeStepSaga.END_STEP);

            UnitOfWorkResult result = placeOrder();

            assertThat(result.rolledBack()).isTrue();
            assertThat(result.stepsRun()).containsExactlyElementsOf(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
            assertNoAggregateChangesRemain();

            SagaInstance instance = harness.instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.outcome()).isNull();
            assertThat(instance.failure().stepName()).isEqualTo(ThreeStepSaga.END_STEP);
            assertThat(instance.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
            assertEveryStepNotRun(instance);
            assertThat(harness.dispatcher().dispatched()).isEmpty();
            assertThat(harness.eventLog().entries(Kind.COMMITTED)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario 4: a SagaContext value written by one step is read by a later step")
    class SagaContextAcrossSteps {

        @Test
        @DisplayName("the start step's value is read by the next step and stored after commit")
        void valueVisibleToLaterStepAndStoredAfterCommit() {
            UnitOfWorkResult result = placeOrder();

            // onItemsReserved throws if the amount is missing, so reaching the outcome proves it was read.
            assertThat(result.committed()).isTrue();
            assertThat(ThreeStepSagaProbe.enteredSteps()).contains(ThreeStepSaga.INVOICED_STEP);
            assertThat(harness.context(result.sagaId())).isEqualTo(Map.of(ThreeStepSaga.CONTEXT_AMOUNT, AMOUNT));
        }

        @Test
        @DisplayName("the value is still there after the unit of work rolls back (FR-024)")
        void valueSurvivesRollback() {
            invoices.failNext();

            UnitOfWorkResult result = placeOrder();

            assertThat(result.rolledBack()).isTrue();
            assertThat(harness.context(result.sagaId())).isEqualTo(Map.of(ThreeStepSaga.CONTEXT_AMOUNT, AMOUNT));
        }
    }

    @Nested
    @DisplayName("Scenario 5: steps run in event order, not declaration order")
    class EventOrderNotDeclarationOrder {

        @Test
        @DisplayName("steps are entered in the order their events are raised")
        void stepsEnteredInEventOrder() {
            placeOrder();

            // ThreeStepSaga declares its steps end step first and start step last; that source order is not
            // observable through reflection (Class.getDeclaredMethods() order is unspecified by the JVM), so
            // the claim rests on the fixture's documented declaration order and is proven through the probe.
            assertThat(ThreeStepSagaProbe.enteredSteps()).containsExactlyElementsOf(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
        }
    }

    @Nested
    @DisplayName("Correlation of non-start events (FR-003)")
    class Correlation {

        @Test
        @DisplayName("a non-start event with no instance is UNMATCHED and has no effect")
        void unmatchedEventHasNoEffect() {
            ItemsReserved event = new ItemsReserved(ReservationService.reservationIdFor(ORDER), ORDER, QUANTITY);

            UnitOfWorkResult result = harness.deliver(event);

            assertThat(result.outcome()).isEqualTo(Outcome.UNMATCHED);
            assertThat(result.sagaId()).isNull();
            assertThat(result.stepsRun()).isEmpty();
            assertThat(harness.eventLog().unmatched()).containsExactly(event);
            assertThat(harness.eventLog().entries()).hasSize(1);
            assertThat(harness.instances(ThreeStepSaga.class)).isEmpty();
            assertThat(ThreeStepSagaProbe.enteredSteps()).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();
            assertNoAggregateChangesRemain();
        }

        @Test
        @DisplayName("a non-start event for a COMPLETED instance is UNEXPECTED and has no effect")
        void unexpectedEventForCompletedInstanceHasNoEffect() {
            String sagaId = placeOrder().sagaId();
            SagaInstance completed = harness.instance(sagaId);
            ThreeStepSagaProbe.reset();
            ItemsReserved event = new ItemsReserved(ReservationService.reservationIdFor(ORDER), ORDER, QUANTITY);

            UnitOfWorkResult result = harness.deliver(event);

            assertThat(result.outcome()).isEqualTo(Outcome.UNEXPECTED);
            assertThat(result.sagaId()).isEqualTo(sagaId);
            assertThat(result.stepsRun()).isEmpty();
            assertThat(harness.eventLog().unexpected()).containsExactly(event);
            assertThat(harness.instance(sagaId)).isEqualTo(completed);
            assertThat(ThreeStepSagaProbe.enteredSteps()).isEmpty();
            assertThat(harness.dispatcher().dispatched()).isEmpty();
            assertThat(reservations.changedAggregates()).containsExactly(ReservationService.reservationIdFor(ORDER));
        }
    }
}
