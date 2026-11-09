package io.github.gkosharovdev.sagaforge.engine.fixtures.fanout;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fan-out fixture saga: {@code @StartSaga onCaseNoted}; {@code @EndSaga onCaseClosed}, which throws while
 * {@link #failOnClose(boolean) failOnClose} is set. All on {@code associationProperty = "caseId"}.
 */
@Saga
public final class ReviewSaga {

    public static final String START_STEP = "onCaseNoted";
    public static final String END_STEP = "onCaseClosed";

    private static final AtomicBoolean FAIL_ON_CLOSE = new AtomicBoolean();

    @SagaId
    private String sagaId;

    /** While set, {@code onCaseClosed} throws; shared by every {@code ReviewSaga} object, so tests reset it. */
    public static void failOnClose(boolean fail) {
        FAIL_ON_CLOSE.set(fail);
    }

    @StartSaga
    @SagaStep(associationProperty = "caseId")
    public void onCaseNoted(CaseNoted event) {
        // Starts the review; nothing to send.
    }

    @EndSaga
    @SagaStep(associationProperty = "caseId")
    public void onCaseClosed(CaseClosed event) {
        if (FAIL_ON_CLOSE.get()) {
            throw new IllegalStateException("ReviewSaga close failure injected");
        }
    }
}
