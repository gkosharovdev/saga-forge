package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * The spec's running example (specs/006-saga-compensation spec.md Context): the order is charged, the saga waits for
 * the delivery partner, and a rejection or a stock shortage refunds the payment. {@code bookCourier} (US3) fails on
 * purpose through {@link CourierService}; its policy allows three attempts without waiting.
 */
@Saga
public final class DeliverySaga {

    public static final String PLACED = "onOrderPlaced";
    public static final String CHARGED = "onCharged";
    public static final String ACCEPTED = "onDeliveryAccepted";
    public static final String REJECTED = "onDeliveryRejected";
    public static final String STOCK = "onStockUnavailable";
    public static final String COURIER = "bookCourier";
    public static final String REFUND = "refundCharge";

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    private final Journal journal;

    DeliverySaga(Journal journal) {
        this.journal = journal;
    }

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onOrderPlaced(OrderPlaced event) {
        context.putState("amount", event.amount());
        commandGateway.send(new Charge(event.orderId(), event.amount()));
    }

    @SagaStep(associationProperty = "orderId")
    public void onCharged(OrderCharged event) {
        context.putState("paymentId", event.paymentId());
        commandGateway.send(new RequestDelivery(event.orderId()));
    }

    @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3))
    public void bookCourier(CourierRequested event) {
        commandGateway.send(new BookCourier(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onDeliveryAccepted(DeliveryAccepted event) {
        // Outcome "onDeliveryAccepted".
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onDeliveryRejected(DeliveryRejected event) {
        context.putState("rejectionReason", event.reason());
    }

    @StartCompensation
    @SagaStep(associationProperty = "orderId")
    public void onStockUnavailable(StockUnavailable event) {
        // The trigger records the cause; nothing else to remember.
    }

    @SagaCompensation(forStep = CHARGED)
    public void refundCharge(CompensationTrigger trigger) {
        journal.ran(REFUND, trigger);
        String paymentId = context.getState("paymentId", String.class).orElseThrow();
        long amount = context.getState("amount", Long.class).orElseThrow();
        try {
            commandGateway.send(new Refund(sagaId, paymentId, amount));
        } catch (RuntimeException refused) {
            if (!journal.swallowsFailures()) {
                throw refused;
            }
            // Caught on purpose: the gateway already reported the failure, so the compensation still fails.
        }
    }
}
