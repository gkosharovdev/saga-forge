package io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Two deadlines (specs/017-saga-deadlines): once stock is reserved, {@code paymentWindow} (duration from the context)
 * waits for the payment and compensates when it expires; a decline cancels it while the saga keeps waiting.
 * {@code reminder} (a property duration) reminds the customer and re-arms itself.
 */
@Saga
public final class PaymentWindowSaga {

    public static final String PLACED = "onOrderPlaced";
    public static final String RESERVED = "onStockReserved";
    public static final String AUTHORIZED = "onPaymentAuthorized";
    public static final String DECLINED = "onPaymentDeclined";
    public static final String EXPIRED = "onPaymentWindowExpired";
    public static final String REMIND = "remind";
    public static final String WINDOW = "paymentWindow";
    public static final String REMINDER = "reminder";

    /** Every DeadlineReached a timeout step received, across instances. */
    public static final List<DeadlineReached> RECEIVED = new CopyOnWriteArrayList<>();

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlaced event) {
        context.putState("orderId", event.orderId());
        context.putState("paymentWindow", event.paymentWindow());
        commandGateway.send(new ReserveStock(event.orderId()));
    }

    @SagaStep(associationProperty = "orderId")
    @StartDeadline(name = WINDOW, durationFrom = "paymentWindow", cancelledBy = DECLINED)
    @StartDeadline(name = REMINDER, duration = "${deadline.reminder:PT10M}")
    public void onStockReserved(StockReserved event) {
    }

    @SagaStep(associationProperty = "orderId")
    public void onPaymentDeclined(PaymentDeclined event) {
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPaymentAuthorized(PaymentAuthorized event) {
    }

    @StartCompensation
    @DeadlineExpired(WINDOW)
    @SagaStep
    public void onPaymentWindowExpired(DeadlineReached deadline) {
        RECEIVED.add(deadline);
    }

    @DeadlineExpired(REMINDER)
    @StartDeadline(name = REMINDER, duration = "${deadline.reminder:PT10M}")
    @SagaStep
    public void remind() {
        commandGateway.send(new RemindCustomer(orderId()));
    }

    @SagaCompensation(forStep = RESERVED)
    public void releaseStock(CompensationTrigger trigger) {
        commandGateway.send(new ReleaseStock(orderId()));
    }

    private String orderId() {
        return context.getState("orderId", String.class).orElseThrow();
    }
}
