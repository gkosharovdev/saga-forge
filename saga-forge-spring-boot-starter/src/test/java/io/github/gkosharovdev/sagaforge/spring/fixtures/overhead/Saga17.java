package io.github.gkosharovdev.sagaforge.spring.fixtures.overhead;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCompletedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;

/** One of 20 sagas for the startup overhead measurement (SC-005). */
@Saga
public class Saga17 {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void start(OrderCreatedEvent event) {
        // waits
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void end(OrderCompletedEvent event) {
        // ends
    }
}
