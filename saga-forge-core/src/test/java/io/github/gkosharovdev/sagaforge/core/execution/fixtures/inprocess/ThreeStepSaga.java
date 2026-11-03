package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * Reference saga for spec 002 User Story 1: every event is raised in-process, so the whole path runs in one
 * saga-scoped unit of work and commits when the {@code @EndSaga} step returns.
 *
 * <h2>Chain (event order)</h2>
 * <ol>
 *   <li>{@code @StartSaga} {@link #onOrderPlaced(OrderPlaced)}: writes {@value #CONTEXT_AMOUNT} into
 *       {@link SagaContext} and sends {@link ReserveItems} ({@link ReservationService} records
 *       {@link ItemsReserved}).</li>
 *   <li>{@link #onItemsReserved(ItemsReserved)}: reads {@value #CONTEXT_AMOUNT} from {@link SagaContext} and
 *       sends {@link IssueInvoice} ({@link InvoiceService} records {@link InvoiceIssued}).</li>
 *   <li>{@link #onInvoiceIssued(InvoiceIssued)}: sends {@link ScheduleShipment} ({@link ShipmentService}
 *       records {@link ShipmentScheduled}).</li>
 *   <li>{@code @EndSaga} {@link #onShipmentScheduled(ShipmentScheduled)}: the outcome step; dispatches
 *       nothing.</li>
 * </ol>
 * <p>Three steps dispatch a command and change one aggregate each ({@link Reservation}, {@link Invoice},
 * {@link Shipment}); the fourth is the outcome. Every step correlates on {@code orderId}.</p>
 *
 * <h2>Declaration order</h2>
 * <p>The methods are deliberately declared in a different order from their events (end step first, start
 * step last). Spec US1 scenario 5 requires steps to run in the order their events are raised, never in
 * declaration order (FR-001); this class lets a test prove it with {@link ThreeStepSagaProbe#enteredSteps()}.
 * Do not reorder them.</p>
 *
 * <p>Each step reports to {@link ThreeStepSagaProbe} on entry and throws an armed failure after doing its
 * work.</p>
 */
@Saga
public class ThreeStepSaga {

    /** Name of the {@link SagaContext} value holding the order amount. */
    public static final String CONTEXT_AMOUNT = "amount";

    public static final String START_STEP = "onOrderPlaced";
    public static final String RESERVED_STEP = "onItemsReserved";
    public static final String INVOICED_STEP = "onInvoiceIssued";
    public static final String END_STEP = "onShipmentScheduled";

    /** Step method names in the order their events are raised (not the declaration order). */
    public static final List<String> STEPS_IN_EVENT_ORDER =
            List.of(START_STEP, RESERVED_STEP, INVOICED_STEP, END_STEP);

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    /** Outcome step: the order is reserved, invoiced, and scheduled for shipment. */
    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onShipmentScheduled(ShipmentScheduled event) {
        ThreeStepSagaProbe.entered(END_STEP);
        ThreeStepSagaProbe.throwIfArmed(END_STEP);
    }

    /** Ships against the issued invoice. */
    @SagaStep(associationProperty = "orderId")
    public void onInvoiceIssued(InvoiceIssued event) {
        ThreeStepSagaProbe.entered(INVOICED_STEP);
        commandGateway.send(new ScheduleShipment(event.orderId(), event.invoiceId()));
        ThreeStepSagaProbe.throwIfArmed(INVOICED_STEP);
    }

    /** Invoices the amount the start step lifted into {@link SagaContext}. */
    @SagaStep(associationProperty = "orderId")
    public void onItemsReserved(ItemsReserved event) {
        ThreeStepSagaProbe.entered(RESERVED_STEP);
        long amount = context.getState(CONTEXT_AMOUNT, Long.class)
                .orElseThrow(() -> new IllegalStateException(
                        "SagaContext value '" + CONTEXT_AMOUNT + "' written by " + START_STEP + " is missing"));
        commandGateway.send(new IssueInvoice(event.orderId(), amount));
        ThreeStepSagaProbe.throwIfArmed(RESERVED_STEP);
    }

    /** Starts the saga: remembers the amount and reserves the items. */
    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlaced event) {
        ThreeStepSagaProbe.entered(START_STEP);
        context.putState(CONTEXT_AMOUNT, event.amount());
        commandGateway.send(new ReserveItems(event.orderId(), event.quantity()));
        ThreeStepSagaProbe.throwIfArmed(START_STEP);
    }
}
