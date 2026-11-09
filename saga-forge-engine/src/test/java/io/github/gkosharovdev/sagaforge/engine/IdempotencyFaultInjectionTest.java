package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.CourierRejected;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.CourierTimeout;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.FlakyNetwork;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.RecordingPause;
import io.github.gkosharovdev.sagaforge.engine.fixtures.retry.RetryFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fault injection across the retried unit of work of {@code RetrySaga} (specs/005-step-retry SC-001, SC-004, SC-005,
 * tasks.md T056): for every failure kind, every number of failures before success, and with or without a crash
 * while waiting and a redelivery after the outcome, the outside payment provider is charged at most once per
 * booking, and no step ever gets more than its 3 attempts. The retried unit of work starts at the saga's start event,
 * so a crash while waiting ends with the redelivered start event as a duplicate start (G-005-06).
 */
class IdempotencyFaultInjectionTest {

    private static final String BOOKING = "booking-1";
    private static final int MAX_ATTEMPTS = 3;

    enum Kind {
        TIMEOUT(() -> new CourierTimeout("timeout"), true),
        FLAKY(() -> new FlakyNetwork("reset"), true),
        REJECTED(() -> new CourierRejected("rejected"), false),
        BUG(() -> new IllegalStateException("bug"), false);

        final Supplier<? extends RuntimeException> failure;
        final boolean retried;

        Kind(Supplier<? extends RuntimeException> failure, boolean retried) {
            this.failure = failure;
            this.retried = retried;
        }
    }

    record Scenario(Kind kind, int failures, boolean crashWhileWaiting, boolean redeliverAfterwards) {

        @Override
        public String toString() {
            return kind + " x" + failures + (crashWhileWaiting ? ", crash" : "")
                    + (redeliverAfterwards ? ", redeliver" : "");
        }
    }

    static Stream<Scenario> scenarios() {
        List<Scenario> scenarios = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (int failures = 0; failures <= MAX_ATTEMPTS + 1; failures++) {
                for (boolean crash : new boolean[] {false, true}) {
                    for (boolean redeliver : new boolean[] {false, true}) {
                        scenarios.add(new Scenario(kind, failures, crash, redeliver));
                    }
                }
            }
        }
        return scenarios.stream();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void atMostOneChargeAndNoExtraAttempts(Scenario scenario) {
        RetryFixture fixture = new RetryFixture();
        fixture.script().thenFail(scenario.failures(), scenario.kind().failure);
        RecordingPause first = scenario.crashWhileWaiting() ? new RecordingPause().crashOnCall(1)
                : new RecordingPause();

        SagaHandlingResult result = book(fixture, fixture.engine(first));
        if (result == null) {
            // Crashed while waiting; the transport redelivers to a restarted engine. A start event finds the
            // instance and is a duplicate start (G-005-06), so the retry stops there.
            result = book(fixture, fixture.engine(new RecordingPause()));
        }
        if (scenario.redeliverAfterwards()) {
            // Redelivery of the in-process event: whatever state the instance is in, nothing runs again. (A
            // redelivered start event after the saga ended would start a new saga: G-005-04.)
            HandlingOutcome redelivered = RetryFixture.charged(fixture.engine(new RecordingPause()), BOOKING)
                    .outcome();
            assertThat(redelivered).isIn(HandlingOutcome.DUPLICATE, HandlingOutcome.UNEXPECTED,
                    HandlingOutcome.NOT_HANDLED);
        }

        assertThat(fixture.provider().charges()).as("charges per idempotency key").hasSizeLessThanOrEqualTo(1);
        assertThat(fixture.provider().charges().values()).containsOnly(100L);
        assertThat(fixture.payments().seen().stream().distinct()).hasSizeLessThanOrEqualTo(1);
        assertThat(fixture.script().calls()).isLessThanOrEqualTo(MAX_ATTEMPTS);
        assertThat(fixture.storage().committed().all()).hasSize(1);
        SagaInstance instance = fixture.storage().committed().all().get(0);
        assertThat(instance.steps().values()).extracting(StepRecord::attempts)
                .allSatisfy(attempts -> assertThat(attempts).isLessThanOrEqualTo(MAX_ATTEMPTS));

        boolean succeeds = scenario.failures() == 0
                || scenario.kind().retried && scenario.failures() < MAX_ATTEMPTS && !scenario.crashWhileWaiting();
        if (succeeds && result != null && result.outcome() != HandlingOutcome.DUPLICATE_START) {
            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
            assertThat(fixture.script().calls()).isEqualTo(scenario.failures() + 1);
        }
    }

    private static SagaHandlingResult book(RetryFixture fixture, SagaEngine engine) {
        try {
            return RetryFixture.book(engine, BOOKING, 100);
        } catch (EventHandlingException crashed) {
            assertThat(crashed.getCause()).isInstanceOf(RecordingPause.Crash.class);
            return null;
        }
    }
}
