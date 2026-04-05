package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;

/**
 * Invalid fixture: no @StartSaga method.
 */
@Saga
public class NoStartSagaSaga {

    @SagaId
    private String sagaId;

    @SagaStep(associationProperty = "orderId")
    public void handleStep(TestEvent event) {
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent2 event) {
    }
}
