package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Starts on {@link ParcelPacked}, which {@link PackingSaga} records and consumes in process; ends
 * on {@link LabelPrinted}. All on {@code associationProperty = "orderId"}.
 */
@Saga
public final class LabelSaga {

  @SagaId private String sagaId;

  @StartSaga
  @SagaStep(associationProperty = "orderId")
  public void onParcelPacked(ParcelPacked event) {
    // Waits for the label.
  }

  @EndSaga
  @SagaStep(associationProperty = "orderId")
  public void onLabelPrinted(LabelPrinted event) {
    // Outcome step.
  }
}
