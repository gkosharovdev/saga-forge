package io.github.gkosharovdev.sagaforge.test.fixtures;

import java.time.Clock;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;

/** A saga with a constructor-injected collaborator and no no-argument constructor, like a Spring bean (US5). */
@Saga
public final class StampSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private final Clock clock;

    public StampSaga(Clock clock) {
        this.clock = clock;
    }

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void placed(OrderPlaced event) {
        context.putState("placedAt", clock.instant().toString());
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void reserved(StockReserved event) {
        context.putState("reservedAt", clock.instant().toString());
    }
}
