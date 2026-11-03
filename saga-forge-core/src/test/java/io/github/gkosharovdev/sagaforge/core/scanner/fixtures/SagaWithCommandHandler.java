package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Invalid fixture: a {@code @Saga} class declares a {@code @CommandHandler} method.
 * Otherwise identical to {@link ValidSaga}, so the only rule it violates is the "no command handler on a
 * saga" rule.
 */
@Saga
public class SagaWithCommandHandler {

    @SagaId
    private String sagaId;

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

    @CommandHandler
    public void handle(TestCommand command) {
    }
}
