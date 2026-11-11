package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * Order module saga: {@code @StartSaga onOrderPlaced} requests payment and waits; {@code onPaymentAuthorized} is an
 * intermediate step; {@code @EndSaga
 * onPaymentConfirmed} books the shipment, whose event starts {@link DeliverySaga} through the outbox. All on
 * {@code associationProperty = "orderId"}.
 */
@Saga
public final class FulfilmentSaga {

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlaced event) {
        commandGateway.send(new RequestPayment(event.orderId()));
    }

    @SagaStep(associationProperty = "orderId")
    public void onPaymentAuthorized(PaymentAuthorized event) {
        // Intermediate step: the saga keeps waiting for the confirmation.
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPaymentConfirmed(PaymentConfirmed event) {
        commandGateway.send(new BookShipment(event.orderId()));
    }
}
