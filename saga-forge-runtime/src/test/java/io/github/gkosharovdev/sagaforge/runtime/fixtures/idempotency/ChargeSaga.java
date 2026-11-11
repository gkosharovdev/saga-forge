package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * Charges an order twice (amount and fee), notifies the customer, then on packing creates and labels a shipment,
 * and ends on delivery. No step declares a retry policy.
 */
@Saga
public final class ChargeSaga {

    /** The fee {@link #onOrderPlaced} charges after the amount. */
    public static final long FEE = 1;

    /** An order of this amount also sends {@link Repack}, recording {@link OrderPacked} twice in-process. */
    public static final long REPACK_AMOUNT = 999;

    public static final String PLACED_STEP = "onOrderPlaced";
    public static final String PACKED_STEP = "onOrderPacked";
    public static final String DELIVERED_STEP = "onOrderDelivered";

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    private final Faults faults;

    ChargeSaga(Faults faults) {
        this.faults = faults;
    }

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlaced event) {
        commandGateway.send(new Charge(event.orderId(), event.amount()));
        commandGateway.send(new Charge(event.orderId(), FEE));
        commandGateway.send(new Notify(event.orderId()));
        if (event.amount() == REPACK_AMOUNT) {
            commandGateway.send(new Repack(event.orderId()));
        }
        faults.check(Faults.AFTER_PLACED);
    }

    @SagaStep(associationProperty = "orderId")
    public void onOrderPacked(OrderPacked event) {
        String shipmentId = Shipment.shipmentIdFor(event.orderId());
        commandGateway.send(new CreateShipment(shipmentId, event.orderId()));
        commandGateway.send(new LabelShipment(shipmentId));
        faults.check(Faults.AFTER_PACKED);
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderDelivered(OrderDelivered event) {
        faults.check(Faults.AFTER_DELIVERED);
    }
}
