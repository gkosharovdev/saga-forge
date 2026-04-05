package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Invalid fixture: @StartSaga without @SagaStep.
 */
@Saga
public class StartSagaWithoutStepSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    public void handleStart(TestEvent event) {
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent2 event) {
    }
}
