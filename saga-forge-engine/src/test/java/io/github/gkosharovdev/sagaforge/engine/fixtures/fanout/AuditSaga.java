package io.github.gkosharovdev.sagaforge.engine.fixtures.fanout;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * Fan-out fixture saga: {@code @StartSaga onCaseOpened} sends {@link AddNote} with the event's mode;
 * {@code onCaseEscalated}; {@code @EndSaga onCaseClosed}. All on {@code associationProperty = "caseId"}.
 */
@Saga
public final class AuditSaga {

    public static final String START_STEP = "onCaseOpened";
    public static final String ESCALATED_STEP = "onCaseEscalated";
    public static final String END_STEP = "onCaseClosed";

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "caseId")
    public void onCaseOpened(CaseOpened event) {
        commandGateway.send(new AddNote(event.caseId(), event.mode()));
    }

    @SagaStep(associationProperty = "caseId")
    public void onCaseEscalated(CaseEscalated event) {
        // Recorded only: the step's completion is what the tests look at.
    }

    @EndSaga
    @SagaStep(associationProperty = "caseId")
    public void onCaseClosed(CaseClosed event) {
        // Outcome step.
    }
}
