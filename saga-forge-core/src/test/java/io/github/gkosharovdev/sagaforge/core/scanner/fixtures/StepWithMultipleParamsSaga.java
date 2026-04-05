package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Invalid fixture: @SagaStep method with two parameters.
 */
@Saga
public class StepWithMultipleParamsSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart(TestEvent event, TestEvent2 extra) {
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent3 event) {
    }
}
