package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * {@code @StartSaga onPackingRequested} sends {@link PackParcel}, whose handler records {@link
 * ParcelPacked}; {@code @EndSaga onParcelPacked} consumes it in process, while {@link LabelSaga}
 * starts on it (specs/014-publish-once Property 47). All on {@code associationProperty =
 * "orderId"}.
 */
@Saga
public final class PackingSaga {

  @SagaId private String sagaId;

  private CommandGateway commandGateway;

  @StartSaga
  @SagaStep(associationProperty = "orderId")
  public void onPackingRequested(PackingRequested event) {
    commandGateway.send(new PackParcel(event.orderId()));
  }

  @EndSaga
  @SagaStep(associationProperty = "orderId")
  public void onParcelPacked(ParcelPacked event) {
    // Outcome step.
  }
}
