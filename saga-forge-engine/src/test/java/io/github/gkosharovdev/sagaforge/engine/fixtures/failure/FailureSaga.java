package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import java.util.List;

/**
 * Failure fixture saga (specs/004-saga-engine tasks.md T056): one unit of work that crosses every failure position of
 * SC-002. Chain, all on {@code associationProperty = "parcelId"}:
 * <ol>
 *   <li>{@code @StartSaga onParcelRequested}: writes {@value #CONTEXT_WEIGHT}, sends {@link ChargeFee}
 *       ({@link FeeService} with a resolved {@link FeeSchedule}, records {@link FeeCharged});</li>
 *   <li>{@code onFeeCharged}: writes {@value #CONTEXT_FEE_ID}, sends {@link ReserveSlot} ({@link Slot} via
 *       {@link SlotRepository}, records {@link SlotReserved});</li>
 *   <li>{@code @EndSaga onSlotReserved}: sends {@link PrintLabel} ({@link Label} via {@link LabelResolver}, records
 *       {@link LabelPrinted}, which no step takes and is handed off after commit).</li>
 * </ol>
 * <p>Each step fails after its work if its {@link FaultPoint} is armed. The {@link FaultPlan} is injected by
 * {@link FailureSagaObjects}.</p>
 */
@Saga
public final class FailureSaga {

    public static final String CONTEXT_WEIGHT = "weight";
    public static final String CONTEXT_FEE_ID = "feeId";

    public static final String START_STEP = "onParcelRequested";
    public static final String FEE_STEP = "onFeeCharged";
    public static final String END_STEP = "onSlotReserved";

    public static final List<String> STEPS_IN_EVENT_ORDER = List.of(START_STEP, FEE_STEP, END_STEP);

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    private final FaultPlan plan;

    FailureSaga(FaultPlan plan) {
        this.plan = plan;
    }

    @StartSaga
    @SagaStep(associationProperty = "parcelId")
    public void onParcelRequested(ParcelRequested event) {
        context.putState(CONTEXT_WEIGHT, event.weight());
        commandGateway.send(new ChargeFee(event.parcelId(), event.weight()));
        plan.endOfStep(START_STEP, FaultPoint.START_STEP);
    }

    @SagaStep(associationProperty = "parcelId")
    public void onFeeCharged(FeeCharged event) {
        context.putState(CONTEXT_FEE_ID, event.feeId());
        try {
            commandGateway.send(new ReserveSlot(Slot.slotIdFor(event.parcelId()), event.parcelId()));
        } catch (InjectedFault fault) {
            if (!plan.catchesInFeeStep()) {
                throw fault;
            }
        }
        plan.endOfStep(FEE_STEP, FaultPoint.FEE_STEP);
    }

    @EndSaga
    @SagaStep(associationProperty = "parcelId")
    public void onSlotReserved(SlotReserved event) {
        commandGateway.send(new PrintLabel(Label.labelIdFor(event.parcelId()), event.parcelId()));
        plan.endOfStep(END_STEP, FaultPoint.END_STEP);
    }
}
