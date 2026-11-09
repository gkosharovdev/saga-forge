package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency.Faults;
import io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency.IdempotencyFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.CourierRejected;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.CourierTimeout;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.RecordingPause;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.RetryFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.RetrySaga;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider.StoredEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Final failures are not retried and the recorded failure says why the saga failed (specs/005-step-retry User
 * Story 4, FR-008 to FR-010, FR-016, FR-017, tasks.md T050).
 */
class FailureClassificationTest {

    private static final String BOOKING = "booking-1";

    private final RetryFixture fixture = new RetryFixture();
    private final RecordingPause pause = new RecordingPause();
    private final SagaEngine engine = fixture.engine(pause);

    private SagaFailure failedWith(SagaHandlingResult result) {
        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        SagaInstance instance = fixture.committed(result.sagaId());
        assertThat(instance.failure()).isEqualTo(result.failure());
        assertThat(instance.pendingRetry()).isNull();
        SagaLifecycleEntry last = fixture.storage().history(result.sagaId()).stream().map(StoredEntry::entry)
                .reduce((first, second) -> second).orElseThrow();
        assertThat(last.kind()).isEqualTo(SagaLifecycleEventKind.FAILED);
        assertThat(last.failure()).isEqualTo(result.failure());
        return result.failure();
    }

    @Test
    @DisplayName("a FinalFailure on the first attempt is not retried: FINAL, attempts 1")
    void finalOnFirstAttempt() {
        fixture.script().thenFail(1, () -> new CourierRejected("no courier for that address"));

        SagaFailure failure = failedWith(RetryFixture.book(engine, BOOKING, 100));

        assertThat(failure.classification()).isEqualTo(FailureClassification.FINAL);
        assertThat(failure.attempts()).isEqualTo(1);
        assertThat(failure.stepName()).isEqualTo(RetrySaga.CHARGED_STEP);
        assertThat(fixture.script().calls()).isEqualTo(1);
        assertThat(pause.waits()).isEmpty();
    }

    @Test
    @DisplayName("an unmarked failure the policy does not list is not retried: UNCLASSIFIED, attempts 1")
    void unclassified() {
        fixture.script().thenFail(1, () -> new IllegalStateException("bug"));

        SagaFailure failure = failedWith(RetryFixture.book(engine, BOOKING, 100));

        assertThat(failure.classification()).isEqualTo(FailureClassification.UNCLASSIFIED);
        assertThat(failure.attempts()).isEqualTo(1);
        assertThat(fixture.script().calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transient failure on every attempt ends EXHAUSTED with attempts 3 and the last cause")
    void exhausted() {
        fixture.script().thenFail(3, () -> new CourierTimeout("courier timed out"));

        SagaFailure failure = failedWith(RetryFixture.book(engine, BOOKING, 100));

        assertThat(failure.classification()).isEqualTo(FailureClassification.EXHAUSTED);
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(failure.causeType()).isEqualTo(CourierTimeout.class.getName());
        assertThat(failure.message()).isEqualTo("courier timed out");
        assertThat(fixture.script().calls()).isEqualTo(3);
        assertThat(pause.waits()).hasSize(2);
        assertThat(fixture.logger().byEvent("saga.uow.exhausted")).singleElement()
                .satisfies(logged -> assertThat(logged.keys()).containsEntry("attempts", "3"));
    }

    @Test
    @DisplayName("transient, transient, then final ends FINAL with attempts 3")
    void finalAfterRetries() {
        fixture.script().thenFail(2, () -> new CourierTimeout("courier timed out"))
                .thenFail(1, () -> new CourierRejected("rejected"));

        SagaFailure failure = failedWith(RetryFixture.book(engine, BOOKING, 100));

        assertThat(failure.classification()).isEqualTo(FailureClassification.FINAL);
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(failure.causeType()).isEqualTo(CourierRejected.class.getName());
    }

    @Test
    @DisplayName("a step without a policy gets one attempt and its failure is UNCLASSIFIED")
    void withoutAPolicy() {
        IdempotencyFixture idempotency = new IdempotencyFixture();
        idempotency.faults().fail(Faults.AFTER_PLACED, 1, () -> new IllegalStateException("bug"));

        SagaHandlingResult result = idempotency.place("order-1", 100);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().classification()).isEqualTo(FailureClassification.UNCLASSIFIED);
        assertThat(result.failure().attempts()).isEqualTo(1);
        assertThat(idempotency.faults().reached(Faults.AFTER_PLACED)).isEqualTo(1);
    }
}
