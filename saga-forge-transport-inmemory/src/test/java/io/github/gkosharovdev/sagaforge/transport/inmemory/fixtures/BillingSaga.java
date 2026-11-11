package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;


import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;

/**
 * Billing module saga: {@code @StartSaga onInvoiceIssued} sends the logged {@link ChargeCard}; {@code @EndSaga
 * onCardCharged}. All on {@code associationProperty = "invoiceId"}.
 */
@Saga
public final class BillingSaga {

    public static final String START_STEP = "onInvoiceIssued";

    @SagaId
    private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "invoiceId")
    public void onInvoiceIssued(InvoiceIssued event) {
        commandGateway.send(new ChargeCard(event.invoiceId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "invoiceId")
    public void onCardCharged(CardCharged event) {
        // Outcome step.
    }
}
