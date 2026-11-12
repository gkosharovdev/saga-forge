package io.github.gkosharovdev.sagaforge.test.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/** Stores whatever value its start event carries in its {@code SagaContext} (tasks.md T048). */
@Saga
public final class LedgerSaga {

    @Aggregate
    public static final class Ledger {

        @AggregateIdentifier
        private String ledgerId;
    }

    @DomainEvent(aggregate = Ledger.class)
    public record ValueBooked(@AggregateIdentifier String ledgerId, String key, Object value) {
    }

    @DomainEvent(aggregate = Ledger.class)
    public record LedgerClosed(@AggregateIdentifier String ledgerId) {
    }

    @SagaId
    private String sagaId;

    private SagaContext context;

    @StartSaga
    @SagaStep(associationProperty = "ledgerId")
    public void booked(ValueBooked event) {
        context.putState(event.key(), event.value());
    }

    @EndSaga
    @SagaStep(associationProperty = "ledgerId")
    public void closed(LedgerClosed event) {
    }
}
