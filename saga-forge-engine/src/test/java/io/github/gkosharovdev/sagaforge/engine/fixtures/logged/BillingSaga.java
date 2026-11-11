package io.github.gkosharovdev.sagaforge.engine.fixtures.logged;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * {@code @StartSaga onInvoiceIssued} sends the logged {@link ChargeCard} (then fails if the event says so);
 * {@code @EndSaga onCardCharged}. On {@code associationProperty = "invoiceId"}.
 */
@Saga
public final class BillingSaga {

    public static final String START_STEP = "onInvoiceIssued";

    private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    public static void reset() {
        ATTEMPTS.clear();
    }

    @StartSaga
    @SagaStep(associationProperty = "invoiceId", retry = @RetryPolicy(maxAttempts = 5, initialBackoffMillis = 0))
    public void onInvoiceIssued(InvoiceIssued event) {
        commandGateway.send(new ChargeCard(event.invoiceId()));
        int attempt = ATTEMPTS.computeIfAbsent(event.invoiceId(), id -> new AtomicInteger()).incrementAndGet();
        if (attempt <= event.transientFailures()) {
            throw new StepFailure("attempt " + attempt + " failed");
        }
        if (event.failAfterSend()) {
            throw new IllegalStateException("step failed after sending");
        }
    }

    @EndSaga
    @SagaStep(associationProperty = "invoiceId")
    public void onCardCharged(CardCharged event) {
        // Outcome step.
    }
}
