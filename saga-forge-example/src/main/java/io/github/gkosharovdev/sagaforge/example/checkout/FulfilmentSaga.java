package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CancelOrderCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CapturePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.CommitStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleasePaymentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.ReleaseStockCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.commands.RequestShipmentCommand;
import io.github.gkosharovdev.sagaforge.example.checkout.events.DeliveryImpossibleEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.OrderConfirmedEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.ShipmentDeliveredEvent;
import io.github.gkosharovdev.sagaforge.example.checkout.events.ShipmentDispatchedEvent;

/**
 * Fulfilment: from a confirmed order to a delivered one, over hours or days, after the customer has left. The saga
 * asks Logistics to deliver, takes the payment and the stock for good once the parcel is dispatched, and gives both
 * back when delivery turns out to be impossible.
 *
 * <p>It starts from {@code OrderConfirmedEvent}, recorded at the end of {@link CheckoutSaga}, which carries every id it
 * needs.</p>
 *
 * <table>
 *   <caption>Steps of the fulfilment</caption>
 *   <tr><th>Step</th><th>On</th><th>Sends</th></tr>
 *   <tr><td>{@code onOrderConfirmed} ({@code @StartSaga}); starts deadline {@code courierAssignment}</td>
 *       <td>{@link OrderConfirmedEvent}</td><td>{@link RequestShipmentCommand} (outbox, to Logistics)</td></tr>
 *   <tr><td>{@code onShipmentDispatched}</td><td>{@link ShipmentDispatchedEvent}</td>
 *       <td>{@link CapturePaymentCommand}, {@link CommitStockCommand} (outbox)</td></tr>
 *   <tr><td>{@code onShipmentDelivered} ({@code @EndSaga})</td><td>{@link ShipmentDeliveredEvent}</td><td>-</td></tr>
 *   <tr><td>{@code onDeliveryImpossible} ({@code @StartCompensation})</td><td>{@link DeliveryImpossibleEvent}</td>
 *       <td>-; {@code cancelOrder} follows</td></tr>
 *   <tr><td>{@code onCourierNotAssigned} ({@code @StartCompensation})</td><td>deadline
 *       {@code courierAssignment}</td><td>-; {@code cancelOrder} follows</td></tr>
 * </table>
 *
 * <p>Logistics has {@code checkout.courier-assignment} (two hours unless the property says otherwise) to find a courier.
 * The dispatch cancels that deadline; the delivery itself may then take days, with no deadline.</p>
 */
@Saga
public class FulfilmentSaga {

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    /** Asks Logistics to deliver the order, then waits for the parcel's progress; a courier must be found in time. */
    @StartSaga
    @SagaStep(associationProperty = "orderId")
    @StartDeadline(name = "courierAssignment", duration = "${checkout.courier-assignment:PT2H}",
            cancelledBy = "onShipmentDispatched")
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        context.putState("orderId", event.orderId());
        context.putState("reservationId", event.reservationId());
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new RequestShipmentCommand(event.orderId(), event.lines(), event.deliveryAddress()));
    }

    /** The parcel is on its way: take the authorised money and the reserved stock for good. */
    @SagaStep(associationProperty = "orderId")
    public void onShipmentDispatched(ShipmentDispatchedEvent event) {
        context.putState("shipmentId", event.shipmentId());
        commandGateway.send(new CapturePaymentCommand(event.orderId(), paymentId()));
        commandGateway.send(new CommitStockCommand(event.orderId(), reservationId()));
    }

    /** Outcome "delivered". */
    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onShipmentDelivered(ShipmentDeliveredEvent event) {
        // Nothing left to do: the money and the stock were taken at dispatch.
    }

    /** Failure outcome: Logistics cannot deliver. The order is cancelled and the customer's money given back. */
    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onDeliveryImpossible(DeliveryImpossibleEvent event) {
        context.putState("cancellationReason", event.reason());
    }

    /** Failure outcome: no courier took the parcel in time. The order is cancelled and the money given back. */
    @StartCompensation
    @DeadlineExpired("courierAssignment")
    @SagaStep
    public void onCourierNotAssigned() {
        context.putState("cancellationReason", "no courier assigned");
    }

    /**
     * Undoes {@code onOrderConfirmed}, and with it everything the order holds: Payments refunds a captured payment or
     * voids an authorised one, Inventory books the stock back in or drops the reservation, and the order is cancelled.
     * Payments and Inventory decide which, from their own state, so this compensation is the same before and after
     * dispatch.
     */
    @SagaCompensation(forStep = "onOrderConfirmed")
    public void cancelOrder() {
        String orderId = context.getState("orderId", String.class).orElseThrow();
        String reason = context.getState("cancellationReason", String.class).orElse("not deliverable");
        commandGateway.send(new ReleasePaymentCommand(orderId, paymentId()));
        commandGateway.send(new ReleaseStockCommand(orderId));
        commandGateway.send(new CancelOrderCommand(orderId, reason));
    }

    private String paymentId() {
        return context.getState("paymentId", String.class).orElseThrow();
    }

    private String reservationId() {
        return context.getState("reservationId", String.class).orElseThrow();
    }
}
