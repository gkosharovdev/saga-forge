package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Invalid fixture: two {@code @SagaStep} methods react to the same event type ({@link TestEvent}).
 * Otherwise identical to {@link ValidSaga}, so the only rule it violates is the "one step per event type"
 * rule.
 */
@Saga
public class DuplicateEventTypeSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart(TestEvent event) {
    }

    @SagaStep(associationProperty = "orderId")
    public void handleAgain(TestEvent event) {
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent2 event) {
    }

    @SagaCompensation(forStep = "handleStart")
    public void compensateStart() {
    }
}
