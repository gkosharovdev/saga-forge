package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderReadyToShipEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.StockUnavailableEvent;

/**
 * A second saga started by an event the order saga records but does not take, so it is reached through the in-memory
 * transport (an event the order saga itself runs would not be handed on, memory.md G-ENG-24).
 */
@Saga
public class FulfilmentSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onReadyToShip(OrderReadyToShipEvent event) {
        // waits
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onStockUnavailable(StockUnavailableEvent event) {
        // never reached in these tests
    }
}
