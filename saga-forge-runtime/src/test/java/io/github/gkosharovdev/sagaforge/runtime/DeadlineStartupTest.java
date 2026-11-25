package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.Order;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.StockService;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 5: misconfigured deadlines fail at startup (specs/017-saga-deadlines spec.md US5, FR-010, FR-016;
 * tasks.md T028, T036).
 */
class DeadlineStartupTest {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();

    private SagaRuntime.Builder builder() {
        return SagaRuntime.builder()
                .register(PaymentWindowSaga.class, StockService.class, Order.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(new MapHandlerInstanceProvider().register(new StockService()));
    }

    @Test
    @DisplayName("deadlines over the default ImmediateDispatchOutbox refuse to start, naming saga, deadline and fix")
    void defaultOutboxCannotSchedule() {
        SagaRuntime runtime = builder().build();

        assertThatThrownBy(runtime::start).isInstanceOf(SagaRuntimeStartupException.class)
                .hasMessageContaining("Saga " + PaymentWindowSaga.class.getName() + " declares deadline")
                .hasMessageContaining("the outbox (ImmediateDispatchOutbox) cannot schedule messages")
                .hasMessageContaining("InMemoryTransport with PostgresMessageStore");
    }

    @Test
    @DisplayName("a ${key} that resolves to no ISO-8601 duration refuses to start")
    void unparseableProperty() {
        Function<String, Optional<String>> properties = key -> Optional.of("ten minutes");
        SagaRuntime runtime = builder().outbox(new SchedulingOutbox(boundary)).propertyResolver(properties).build();

        assertThatThrownBy(runtime::start).isInstanceOf(SagaRuntimeStartupException.class)
                .hasMessageContaining("deadline 'reminder' on step 'onStockReserved' has duration"
                        + " '${deadline.reminder:PT10M}', resolved to 'ten minutes', which is not an ISO-8601 duration");
    }

    @Test
    @DisplayName("over a scheduling outbox with resolvable durations the runtime starts")
    void starts() {
        builder().outbox(new SchedulingOutbox(boundary)).build().start();
    }
}
