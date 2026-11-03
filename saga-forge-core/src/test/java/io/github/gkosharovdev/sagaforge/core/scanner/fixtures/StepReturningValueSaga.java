package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Invalid fixture: a {@code @SagaStep} method returns a value instead of {@code void}.
 * Otherwise identical to {@link ValidSaga}, so the only rule it violates is the "steps return void" rule.
 */
@Saga
public class StepReturningValueSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public TestCommand handleStart(TestEvent event) {
        return new TestCommand(event.orderId());
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent2 event) {
    }

    @SagaCompensation(forStep = "handleStart")
    public void compensateStart() {
    }
}
