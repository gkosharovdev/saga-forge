package io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import java.util.Optional;

/**
 * Concurrency fixture saga, all steps on {@code associationProperty = "ticketId"} and none sending commands:
 * {@code @StartSaga onTicketOpened} waits; {@code onSignalA} writes {@value #CONTEXT_A} and reports the
 * {@value #CONTEXT_B} it saw; {@code onSignalB} the other way round; {@code @EndSaga onTicketClosed}. Every step
 * reports to the {@link StepProbe} injected by {@link SignalSagaObjects}.
 */
@Saga
public final class SignalSaga {

    public static final String CONTEXT_A = "a";
    public static final String CONTEXT_B = "b";

    public static final String START_STEP = "onTicketOpened";
    public static final String SIGNAL_A_STEP = "onSignalA";
    public static final String SIGNAL_B_STEP = "onSignalB";
    public static final String END_STEP = "onTicketClosed";

    @SagaId
    private String sagaId;

    private SagaContext context;

    private final StepProbe probe;

    SignalSaga(StepProbe probe) {
        this.probe = probe;
    }

    @StartSaga
    @SagaStep(associationProperty = "ticketId")
    public void onTicketOpened(TicketOpened event) {
        probe.enter(sagaId, START_STEP);
        probe.exit(sagaId, START_STEP);
    }

    @SagaStep(associationProperty = "ticketId")
    public void onSignalA(SignalA event) {
        signal(SIGNAL_A_STEP, CONTEXT_A, CONTEXT_B);
    }

    @SagaStep(associationProperty = "ticketId")
    public void onSignalB(SignalB event) {
        signal(SIGNAL_B_STEP, CONTEXT_B, CONTEXT_A);
    }

    @EndSaga
    @SagaStep(associationProperty = "ticketId")
    public void onTicketClosed(TicketClosed event) {
        probe.enter(sagaId, END_STEP);
        probe.exit(sagaId, END_STEP);
    }

    private void signal(String step, String own, String other) {
        probe.enter(sagaId, step);
        Optional<String> seen = context.getState(other, String.class);
        context.putState(own, step);
        probe.saw(step, seen);
        probe.exit(sagaId, step);
    }
}
