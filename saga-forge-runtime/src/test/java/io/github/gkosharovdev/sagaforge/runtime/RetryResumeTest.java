package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.CourierTimeout;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.DeliveryPayments;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.RecordingPause;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.RetryFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.retry.RetrySaga;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retries survive a crash and an interrupted wait without granting extra attempts (specs/005-step-retry User
 * Story 5, FR-014b, FR-015, SC-005, research §9, tasks.md T055). Two engines over one set of stores stand in for a
 * process before and after a restart; the retrying step is triggered by a non-start event.
 */
class RetryResumeTest {

    private static final String BOOKING = "booking-1";

    private final RetryFixture fixture = new RetryFixture();
    private String sagaId;

    @BeforeEach
    void waitingForTheCharge() {
        SagaRuntime setup = fixture.engine(new RecordingPause());
        SagaHandlingResult booked = RetryFixture.book(setup, BOOKING, DeliveryPayments.CHARGED_ELSEWHERE);
        assertThat(booked.outcome()).isEqualTo(HandlingOutcome.WAITING);
        sagaId = booked.sagaId();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    @DisplayName("a crash while waiting for attempt 2 counts it as used: the redelivery runs attempt 3 and no 4th")
    void crashWhileWaiting() {
        fixture.script().thenFail(2, () -> new CourierTimeout("courier timed out"));
        SagaRuntime before = fixture.engine(new RecordingPause().crashOnCall(1));

        EventHandlingException crashed = catchThrowableOfType(EventHandlingException.class,
                () -> RetryFixture.charged(before, BOOKING));

        assertThat(crashed).isNotNull();
        PendingRetry claimed = fixture.committed(sagaId).pendingRetry();
        assertThat(claimed.triggerStep()).isEqualTo(RetrySaga.CHARGED_STEP);
        assertThat(claimed.attemptsStarted()).isEqualTo(2);
        assertThat(claimed.lastFailure().attempts()).isEqualTo(1);

        RecordingPause afterPause = new RecordingPause();
        SagaHandlingResult result = RetryFixture.charged(fixture.engine(afterPause), BOOKING);

        // Since 006 the exhausted retry starts compensation: the start step had committed (tasks.md T034).
        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        SagaFailure exhausted = fixture.committed(sagaId).compensationTrigger().failure();
        assertThat(fixture.committed(sagaId).compensationTrigger().kind())
                .isEqualTo(CompensationTriggerKind.RETRIES_EXHAUSTED);
        assertThat(exhausted.classification()).isEqualTo(FailureClassification.EXHAUSTED);
        assertThat(exhausted.attempts()).isEqualTo(3);
        assertThat(fixture.script().calls()).isEqualTo(2);
        assertThat(afterPause.waits()).isEmpty();
    }

    @Test
    @DisplayName("after a crash the resumed attempt can succeed; its steps record attempt 3")
    void crashThenSuccess() {
        fixture.script().thenFail(1, () -> new CourierTimeout("courier timed out"));
        SagaRuntime before = fixture.engine(new RecordingPause().crashOnCall(1));
        catchThrowableOfType(EventHandlingException.class, () -> RetryFixture.charged(before, BOOKING));

        SagaHandlingResult result = RetryFixture.charged(fixture.engine(new RecordingPause()), BOOKING);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.pendingRetry()).isNull();
        assertThat(instance.steps().get(RetrySaga.CHARGED_STEP).attempts()).isEqualTo(3);
        assertThat(instance.steps().get(RetrySaga.BOOKED_STEP).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an interrupted wait is NOT_HANDLED, keeps the claim and the interrupt flag; the redelivery finds "
            + "no attempt left")
    void interruptedWait() {
        fixture.script().thenFail(2, () -> new CourierTimeout("courier timed out"));
        SagaRuntime before = fixture.engine(new RecordingPause().interruptOnCall(2));

        SagaHandlingResult interrupted = RetryFixture.charged(before, BOOKING);

        assertThat(interrupted.outcome()).isEqualTo(HandlingOutcome.NOT_HANDLED);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(fixture.committed(sagaId).pendingRetry().attemptsStarted()).isEqualTo(3);
        assertThat(fixture.logger().byEvent("saga.uow.retryInterrupted")).hasSize(1);

        SagaHandlingResult redelivered = RetryFixture.charged(fixture.engine(new RecordingPause()), BOOKING);

        // Since 006 the exhausted retry starts compensation: the start step had committed (tasks.md T034).
        assertThat(redelivered.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        SagaFailure exhausted = fixture.committed(sagaId).compensationTrigger().failure();
        assertThat(exhausted.classification()).isEqualTo(FailureClassification.EXHAUSTED);
        assertThat(exhausted.attempts()).isEqualTo(3);
        assertThat(exhausted.causeType()).isEqualTo(CourierTimeout.class.getName());
        assertThat(fixture.script().calls()).isEqualTo(2);
    }
}
