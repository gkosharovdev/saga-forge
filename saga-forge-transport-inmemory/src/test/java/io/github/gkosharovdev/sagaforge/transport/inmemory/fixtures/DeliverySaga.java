package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/** Shipping module saga: {@code @StartSaga onShipmentBooked}, {@code @EndSaga onShipmentDelivered}, on orderId. */
@Saga
public final class DeliverySaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onShipmentBooked(ShipmentBooked event) {
        // Waits for the delivery.
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onShipmentDelivered(ShipmentDelivered event) {
        // Outcome step.
    }
}
