package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Invalid fixture: no @EndSaga method.
 */
@Saga
public class NoEndSagaSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart(TestEvent event) {
    }

    @SagaStep(associationProperty = "orderId")
    public void handleStep(TestEvent2 event) {
    }
}
