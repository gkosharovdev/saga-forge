package io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded;


import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * {@code @StartSaga onOpened} sends {@link TriageTicket}, whose {@link TicketTriaged} runs {@code onTriaged} of the same
 * instance in process; the saga then waits for {@code @EndSaga onResolved}. All on {@code associationProperty =
 * "ticketId"}. Every event of the first unit of work runs in process: nothing is handed on.
 */
@Saga
public final class TicketSaga {

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "ticketId")
    public void onOpened(TicketOpened event) {
        commandGateway.send(new TriageTicket(event.ticketId()));
    }

    @SagaStep(associationProperty = "ticketId")
    public void onTriaged(TicketTriaged event) {
        // Waits for the resolution.
    }

    @EndSaga
    @SagaStep(associationProperty = "ticketId")
    public void onResolved(TicketResolved event) {
        // Outcome step.
    }
}
