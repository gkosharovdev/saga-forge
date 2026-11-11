package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc;

import java.time.Clock;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.ChargeOrder;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.ConfirmOrder;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.OrderCharged;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.OrderConfirmed;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.OrderPlaced;

/**
 * A prototype-scoped saga bean with a constructor-injected {@link Clock} (US2 scenario 1); its {@code SagaContext} and
 * {@code CommandGateway} fields are still supplied by the runtime.
 */
@Saga
public class JdbcOrderSaga {

    private final Clock clock;

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    public JdbcOrderSaga(Clock clock) {
        this.clock = clock;
    }

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onPlaced(OrderPlaced event) {
        context.putState("placedAt", clock.instant().toString());
        commandGateway.send(new ChargeOrder(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void onCharged(OrderCharged event) {
        context.putState("provider", event.provider());
        commandGateway.send(new ConfirmOrder(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onConfirmed(OrderConfirmed event) {
        // outcome
    }
}
