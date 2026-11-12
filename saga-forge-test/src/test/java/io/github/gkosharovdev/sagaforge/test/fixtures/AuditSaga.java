package io.github.gkosharovdev.sagaforge.test.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;

/** A second saga type reacting to the shop's events: it opens on every placed order and closes on its reservation. */
@Saga
public final class AuditSaga {

    @SagaId
    private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void opened(OrderPlaced event) {
        // Nothing to dispatch.
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void closed(StockReserved event) {
        // Terminal step.
    }
}
