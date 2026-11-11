package io.github.gkosharovdev.sagaforge.spring.modulith.app.orders;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.payments.PaymentEvents.PaymentTaken;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.payments.PaymentEvents.TakePayment;

/** Takes the payment for a placed order; refuses a negative amount after the payment was recorded. */
@Saga
public class PaymentSaga {

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onPlaced(OrderPlaced event) {
        commandGateway.send(new TakePayment(event.orderId(), event.amount()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPaid(PaymentTaken event) {
        if (event.amount().signum() < 0) {
            throw new IllegalStateException("negative payment for " + event.orderId());
        }
    }
}
