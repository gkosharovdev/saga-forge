package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.CourierTimeout;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.Delivery;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.FlakyNetwork;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.RecordingPause;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.RetryFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.RetrySaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.SlowRetrySaga;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider.StoredEntry;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A transient failure is retried under the failing step's policy (specs/005-step-retry User Story 3, FR-011 to
 * FR-014a, contracts/attempt-loop.md, tasks.md T046).
 */
class StepRetryTest {

    private static final String BOOKING = "booking-1";

    private final RetryFixture fixture = new RetryFixture();
    private final RecordingPause pause = new RecordingPause();
    private final SagaRuntime engine = fixture.engine(pause);

    private List<SagaLifecycleEntry> history(String sagaId) {
        return fixture.storage().history(sagaId).stream().map(StoredEntry::entry).toList();
    }

    @Test
    @DisplayName("two transient failures, then success: one commit, attempts 3, waits 200 and 400 ms")
    void transientThenSuccess() {
        fixture.script().thenFail(2, () -> new CourierTimeout("courier timed out"));

        SagaHandlingResult result = RetryFixture.book(engine, BOOKING, 100);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(result.unitOfWorkId()).isEqualTo("uow-3");
        assertThat(pause.waits()).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
        SagaInstance instance = fixture.committed(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(instance.pendingRetry()).isNull();
        assertThat(instance.steps().values()).extracting(StepRecord::attempts).containsOnly(3);
        assertThat(fixture.context(result.sagaId())).containsEntry(RetrySaga.CONTEXT_TRIES, 3);

        List<SagaLifecycleEntry> history = history(result.sagaId());
        assertThat(history).extracting(SagaLifecycleEntry::kind).containsExactly(
                SagaLifecycleEventKind.CREATED, SagaLifecycleEventKind.ATTEMPT_FAILED,
                SagaLifecycleEventKind.ATTEMPT_FAILED, SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED,
                SagaLifecycleEventKind.COMPLETED);
        assertThat(history.subList(1, 3)).allSatisfy(entry -> {
            assertThat(entry.stepName()).isEqualTo(RetrySaga.CHARGED_STEP);
            assertThat(entry.failure().classification()).isEqualTo(FailureClassification.TRANSIENT);
            assertThat(entry.failure().causeType()).isEqualTo(CourierTimeout.class.getName());
        });
        assertThat(history.subList(1, 3)).extracting(entry -> entry.failure().attempts()).containsExactly(1, 2);
        assertThat(fixture.logger().byEvent("saga.uow.retrying")).hasSize(2);
    }

    @Test
    @DisplayName("re-running the earlier step of the unit of work re-sends its command with the same identity: one "
            + "charge")
    void earlierStepKeepsItsIdentity() {
        fixture.script().thenFail(2, () -> new CourierTimeout("courier timed out"));

        SagaHandlingResult result = RetryFixture.book(engine, BOOKING, 100);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(fixture.payments().seen()).hasSize(3).containsOnly(fixture.payments().seen().get(0));
        assertThat(fixture.provider().calls()).hasSize(3);
        assertThat(fixture.provider().charges()).hasSize(1).containsValue(100L);
        assertThat(fixture.committed(result.sagaId()).steps().get(RetrySaga.BOOKED_STEP).handledCommands())
                .containsExactly(fixture.payments().seen().get(0));
    }

    @Test
    @DisplayName("a failure marked TransientFailure is retried although retryOn does not list it")
    void markedTransient() {
        fixture.script().thenFail(1, () -> new FlakyNetwork("connection reset"));

        SagaHandlingResult result = RetryFixture.book(engine, BOOKING, 100);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(fixture.committed(result.sagaId()).steps().get(RetrySaga.END_STEP).attempts()).isEqualTo(2);
        assertThat(pause.waits()).containsExactly(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("a policy whose total wait exceeds the engine maximum is refused at startup")
    void totalBackoffBound() {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        SagaRuntime.Builder builder = SagaRuntime.builder()
                .register(SlowRetrySaga.class, Delivery.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher());

        SagaRuntimeStartupException refused = catchThrowableOfType(SagaRuntimeStartupException.class,
                builder.build()::start);

        assertThat(refused).isNotNull();
        assertThat(refused.report().engineErrors()).containsExactly("@SagaStep SlowRetrySaga.onSlowBooked: total"
                + " retry backoff 45000 ms exceeds the engine maximum 30000 ms");

        SagaRuntime raised = builder.maxTotalRetryBackoff(Duration.ofMinutes(1)).build();
        raised.start();
        assertThat(raised.isStarted()).isTrue();
    }
}
