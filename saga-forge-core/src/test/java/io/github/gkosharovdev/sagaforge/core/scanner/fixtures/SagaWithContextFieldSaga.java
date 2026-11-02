package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * A fully valid saga class that declares exactly one {@link SagaContext}-typed field.
 * The field is undecorated: it is recognized by its declared type alone, with no marker annotation.
 * Used to prove the scanner accepts a single {@code SagaContext} field (the "at most one" rule).
 */
@Saga
public class SagaWithContextFieldSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart(TestEvent event) {
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent2 event) {
    }

    @SagaCompensation(forStep = "handleStart")
    public void compensateStart() {
    }
}
