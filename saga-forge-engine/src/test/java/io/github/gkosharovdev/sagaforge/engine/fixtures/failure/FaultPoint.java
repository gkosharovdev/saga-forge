package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

/** Where {@link FaultPlan} can make {@link FailureSaga}'s unit of work fail (SC-002 positions). */
public enum FaultPoint {
    /** {@code onParcelRequested}, after it sent {@link ChargeFee}. */
    START_STEP(FailureSaga.START_STEP),
    /** {@code onFeeCharged}, after it sent {@link ReserveSlot}. */
    FEE_STEP(FailureSaga.FEE_STEP),
    /** {@code onSlotReserved}, after it sent {@link PrintLabel}. */
    END_STEP(FailureSaga.END_STEP),
    /** {@link FeeService}, after it recorded the fee. */
    FEE_HANDLER(FailureSaga.START_STEP),
    /** {@link Slot}'s handler, after it reserved the slot. */
    SLOT_HANDLER(FailureSaga.FEE_STEP),
    /** {@link Label}'s handler, after it printed the label. */
    LABEL_HANDLER(FailureSaga.END_STEP),
    /** {@link SlotRepository}'s loader. */
    SLOT_LOADER(FailureSaga.FEE_STEP),
    /** {@link SlotRepository}'s saver. */
    SLOT_SAVER(FailureSaga.FEE_STEP),
    /** {@link LabelResolver#load}. */
    RESOLVER_LOAD(FailureSaga.END_STEP),
    /** {@link LabelResolver#save}. */
    RESOLVER_SAVE(FailureSaga.END_STEP),
    /** {@link FailingHandlerInstances}, at its first lookup (the {@link FeeService} instance). */
    HANDLER_INSTANCE_PROVIDER(FailureSaga.START_STEP),
    /** {@link FeeScheduleResolver#resolve}. */
    PARAMETER_RESOLVER(FailureSaga.START_STEP);

    private final String failingStep;

    FaultPoint(String failingStep) {
        this.failingStep = failingStep;
    }

    /** The step whose code runs when this point fails, which the {@code SagaFailure} must name. */
    public String failingStep() {
        return failingStep;
    }

    /** {@code true} if {@code onFeeCharged} has written {@link FailureSaga#CONTEXT_FEE_ID} before this point fails. */
    public boolean afterFeeIdWritten() {
        return !failingStep.equals(FailureSaga.START_STEP);
    }
}
