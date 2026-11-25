package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * A hold that lapses ({@code specs/017-saga-deadlines}): {@code @StartSaga onHoldPlaced} starts the {@code lapse}
 * deadline, {@code @EndSaga onHoldConfirmed} ends it in time, {@code @StartCompensation expire} runs when it is due.
 * All on {@code associationProperty = "holdId"}.
 */
@Saga
public final class HoldSaga {

    public static final String LAPSE = "lapse";

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "holdId")
    @StartDeadline(name = LAPSE, duration = "${hold.lapse:PT0.3S}")
    public void onHoldPlaced(HoldPlaced event) {
        // Opens the deadline.
    }

    @EndSaga
    @SagaStep(associationProperty = "holdId")
    public void onHoldConfirmed(HoldConfirmed event) {
        // Confirmed in time.
    }

    @StartCompensation
    @DeadlineExpired(LAPSE)
    @SagaStep
    public void expire() {
        // The hold lapsed.
    }
}
