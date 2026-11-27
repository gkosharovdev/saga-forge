package io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * {@code @StartSaga onRequested} sends {@link PackShipment}, whose handler records {@link
 * ShipmentPacked}; {@code onPacked} consumes it in process and waits; {@code @EndSaga onInvoiced}.
 * All on {@code associationProperty = "orderId"}.
 */
@Saga
public final class PackingSaga {

  public static final String PACKED_STEP = "onPacked";

  @SagaId private String sagaId;

  private CommandGateway commandGateway;

  @StartSaga
  @SagaStep(associationProperty = "orderId")
  public void onRequested(ShipmentRequested event) {
    commandGateway.send(new PackShipment(event.orderId()));
  }

  @SagaStep(associationProperty = "orderId")
  public void onPacked(ShipmentPacked event) {
    // Waits for the invoice.
  }

  @EndSaga
  @SagaStep(associationProperty = "orderId")
  public void onInvoiced(ShipmentInvoiced event) {
    // Outcome step.
  }
}
