package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Invalid fixture: two @StartSaga methods.
 */
@Saga
public class MultipleStartSagaSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart1(TestEvent event) {
    }

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart2(TestEvent2 event) {
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent3 event) {
    }
}
