package io.github.gkosharovdev.sagaforge.core.scanner.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;

/**
 * Invalid fixture: a @SagaStep taking an outcome event, which is a framework event, not a domain
 * event (specs/011-saga-outcome-events FR-017, deferred as G-011-01).
 */
@Saga
public class StepTakingOutcomeEventSaga {

  @SagaId private String sagaId;

  @StartSaga
  @SagaStep(associationProperty = "orderId")
  public void handleStart(TestEvent event) {}

  @EndSaga
  @SagaStep(associationProperty = "sagaId")
  public void otherSagaFailed(SagaFailed outcome) {}
}
