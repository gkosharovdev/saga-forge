package io.github.gkosharovdev.sagaforge.test.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;

/** Violates the model rules twice: two {@code @SagaId} fields, and no {@code @StartSaga} or {@code @EndSaga} step. */
@Saga
public final class BrokenSaga {

    @SagaId
    private String sagaId;

    @SagaId
    private String otherId;

    @SagaStep(associationProperty = "orderId")
    public void placed(OrderPlaced event) {
    }
}
