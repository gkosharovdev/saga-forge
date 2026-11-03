package io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * A valid one-step saga whose event, {@link ReachedEventWithoutIdentifier}, is invalid. Used to show that
 * event types are reached from step signatures.
 */
@Saga
public class ReachingSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @EndSaga
    @SagaStep(associationProperty = "id")
    public void handle(ReachedEventWithoutIdentifier event) {
    }
}
