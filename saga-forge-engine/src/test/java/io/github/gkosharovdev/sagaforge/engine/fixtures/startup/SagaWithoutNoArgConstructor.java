package io.github.gkosharovdev.sagaforge.engine.fixtures.startup;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/** A valid saga whose only constructor takes an argument: runnable only with a supporting provider (Property 23). */
@Saga
public final class SagaWithoutNoArgConstructor {

    @SagaId
    private String sagaId;

    private final String dependency;

    public SagaWithoutNoArgConstructor(String dependency) {
        this.dependency = dependency;
    }

    @StartSaga
    @SagaStep(associationProperty = "thingId")
    public void onStarted(ThingStarted event) {
        // Holds the dependency only; nothing to do.
    }

    @EndSaga
    @SagaStep(associationProperty = "thingId")
    public void onEnded(ThingEnded event) {
        // Outcome step.
    }

    String dependency() {
        return dependency;
    }
}
