package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/** A valid policy whose total wait, 45 s, exceeds the default engine maximum of 30 s. */
@Saga
public final class SlowRetrySaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "bookingId", retry = @RetryPolicy(maxAttempts = 4, initialBackoffMillis = 15_000))
    public void onSlowBooked(SlowBooked event) {
    }

    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onSlowDone(SlowDone event) {
    }
}
