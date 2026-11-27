package io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Starts on {@link ShipmentPacked}, which {@link PackingSaga} records and consumes in process
 * (G-ENG-24); {@code @EndSaga onInvoiced}. All on {@code associationProperty = "orderId"}.
 */
@Saga
public final class BillingSaga {

  @SagaId private String sagaId;

  @StartSaga
  @SagaStep(associationProperty = "orderId")
  public void onPacked(ShipmentPacked event) {
    // Opens the bill.
  }

  @EndSaga
  @SagaStep(associationProperty = "orderId")
  public void onInvoiced(ShipmentInvoiced event) {
    // Outcome step.
  }
}
