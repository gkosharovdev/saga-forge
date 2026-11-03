package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * Invalid fixture: two {@link CommandGateway}-typed fields.
 * Otherwise identical to {@link ValidSaga}, so the only rule it violates is the {@code CommandGateway}
 * "at most one" rule.
 */
@Saga
public class MultipleCommandGatewayFieldSaga {

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    private CommandGateway anotherCommandGateway;

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
