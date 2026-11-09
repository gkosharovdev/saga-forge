package io.github.gkosharovdev.sagaforge.engine.fixtures.retry;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * Books a delivery in one unit of work: {@code onBooked} charges it (an outside effect), {@code onCharged} books a
 * courier, {@code onCourierBooked} ends the saga. {@code onCharged} retries courier timeouts and transient failures:
 * 3 attempts, 200 ms then 400 ms. Each run of {@code onCharged} counts itself in {@link #CONTEXT_TRIES}.
 */
@Saga
public final class RetrySaga {

    public static final String BOOKED_STEP = "onBooked";
    public static final String CHARGED_STEP = "onCharged";
    public static final String END_STEP = "onCourierBooked";
    public static final String CONTEXT_TRIES = "tries";

    @SagaId
    private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "bookingId")
    public void onBooked(DeliveryBooked event) {
        commandGateway.send(new ChargeDelivery(event.bookingId(), event.amount()));
    }

    @SagaStep(associationProperty = "bookingId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
            multiplier = 2.0, maxBackoffMillis = 1_000, retryOn = CourierTimeout.class))
    public void onCharged(DeliveryCharged event) {
        int tries = context.getState(CONTEXT_TRIES, Integer.class).orElse(0);
        context.putState(CONTEXT_TRIES, tries + 1);
        commandGateway.send(new BookCourier(event.bookingId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void onCourierBooked(CourierBooked event) {
        // Terminal step.
    }
}
