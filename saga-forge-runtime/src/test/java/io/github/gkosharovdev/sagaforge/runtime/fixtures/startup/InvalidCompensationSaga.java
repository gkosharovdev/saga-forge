package io.github.gkosharovdev.sagaforge.runtime.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Violates R20, R21 and R22 at once (specs/006-saga-compensation tasks.md T021): a {@code @StartCompensation} method
 * without {@code @SagaStep}, a compensation returning a value, and two compensations for one step.
 */
@Saga
public class InvalidCompensationSaga {

    public static final String R20 = "Method onRejected in InvalidCompensationSaga is annotated with "
            + "@StartCompensation but missing @SagaStep";
    public static final String R21 = "@SagaCompensation method undoTwice in InvalidCompensationSaga must return void";
    public static final String R22 = "@SagaCompensation methods undoOnce, undoTwice in InvalidCompensationSaga both "
            + "compensate step onStarted";

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "thingId")
    public void onStarted(ThingStarted event) {
        // Nothing to do.
    }

    @EndSaga
    @SagaStep(associationProperty = "thingId")
    public void onEnded(ThingEnded event) {
        // Outcome step.
    }

    @StartCompensation
    public void onRejected(ThingRejected event) {
        // Missing @SagaStep (R20).
    }

    @SagaCompensation(forStep = "onStarted")
    public void undoOnce() {
        // First compensation of onStarted.
    }

    @SagaCompensation(forStep = "onStarted")
    public String undoTwice() {
        return "second compensation of onStarted, returning a value";
    }
}
