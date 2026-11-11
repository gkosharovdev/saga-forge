package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * Steps A to D, each in its own unit of work unless chained, each with a compensation; {@code onAbort} starts
 * compensation (specs/006-saga-compensation US2, tasks.md T033).
 */
@Saga
public final class UnwindSaga {

    public static final String A = "a";
    public static final String B = "b";
    public static final String C = "c";
    public static final String D = "d";
    public static final String ABORT = "onAbort";

    /** An {@link UnwindStarted} with this order id also chains B and C into A's unit of work. */
    public static final String CHAINED = "chained";

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    private final Journal journal;

    UnwindSaga(Journal journal) {
        this.journal = journal;
    }

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void a(UnwindStarted event) {
        if (event.orderId().startsWith(CHAINED)) {
            commandGateway.send(new Chain(event.orderId()));
        }
    }

    @SagaStep(associationProperty = "orderId")
    public void b(BDone event) {
        journal.ran("b", null);
    }

    @SagaStep(associationProperty = "orderId")
    public void c(CDone event) {
        journal.ran("c", null);
    }

    @SagaStep(associationProperty = "orderId")
    public void d(DTried event) {
        journal.ran("d", null);
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onFinished(Finished event) {
        // Outcome "onFinished".
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onAbort(Aborted event) {
        // The trigger records the cause.
    }

    @SagaCompensation(forStep = A)
    public void undoA() {
        journal.ran("undoA", null);
    }

    @SagaCompensation(forStep = B)
    public void undoB() {
        journal.ran("undoB", null);
    }

    @SagaCompensation(forStep = C)
    public void undoC() {
        journal.ran("undoC", null);
    }

    @SagaCompensation(forStep = D)
    public void undoD() {
        journal.ran("undoD", null);
    }
}
