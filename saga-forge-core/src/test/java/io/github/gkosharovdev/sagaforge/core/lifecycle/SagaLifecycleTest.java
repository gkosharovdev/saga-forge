package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.gkosharovdev.sagaforge.core.lifecycle.fixtures.InMemorySagaLifecycle;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.SagaWithContextFieldSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.ValidSaga;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Contract-behavior tests for {@link SagaLifecycle} (spec User Story 2), exercised through the
 * test-only {@link InMemorySagaLifecycle} double.
 */
class SagaLifecycleTest {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final String ASSOCIATION_PROPERTY = "orderId";
    private static final String ASSOCIATION_VALUE = "order-1";
    /** {@link ValidSaga}'s {@code @StartSaga} step. */
    private static final String START_STEP = "handleStart";
    /** {@link ValidSaga}'s {@code @EndSaga} step. */
    private static final String END_STEP = "handleEnd";
    private static final AggregateReference ORDER_1 = new AggregateReference("com.example.Order", "order-1");
    private static final AggregateReference PAYMENT_1 = new AggregateReference("com.example.Payment", "payment-1");

    private MutableClock clock;
    private SagaLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        lifecycle = new InMemorySagaLifecycle(clock);
    }

    private SagaInstance startValidSaga() {
        return lifecycle.start(ValidSaga.class, ASSOCIATION_PROPERTY, ASSOCIATION_VALUE);
    }

    @Nested
    @DisplayName("start with no prior active instance (scenario 1)")
    class Start {

        @Test
        @DisplayName("returns a new CREATED instance with id, type, empty state and the association")
        void returnsNewCreatedInstance() {
            SagaInstance instance = startValidSaga();

            assertThat(instance.status()).isEqualTo(SagaStatus.CREATED);
            assertThat(instance.sagaId()).isNotBlank();
            assertThat(instance.sagaType()).isEqualTo(ValidSaga.class.getName());
            assertThat(instance.state()).isEmpty();
            assertThat(instance.associations()).containsExactly(entry(ASSOCIATION_PROPERTY, ASSOCIATION_VALUE));
        }

        @Test
        @DisplayName("stamps createdAt and lastModifiedAt with the same clock instant")
        void timestampsComeFromClock() {
            SagaInstance instance = startValidSaga();

            assertThat(instance.createdAt()).isEqualTo(T0);
            assertThat(instance.lastModifiedAt()).isEqualTo(instance.createdAt());
        }
    }

    @Nested
    @DisplayName("advance reflects progress (scenario 2)")
    class Advance {

        @Test
        @DisplayName("first advance moves CREATED to RUNNING and preserves identity and associations")
        void firstAdvanceMovesToRunning() {
            SagaInstance started = startValidSaga();
            clock.advance(Duration.ofSeconds(5));

            SagaInstance advanced = lifecycle.advance(started.sagaId());

            assertThat(advanced.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(advanced.sagaId()).isEqualTo(started.sagaId());
            assertThat(advanced.sagaType()).isEqualTo(started.sagaType());
            assertThat(advanced.associations()).isEqualTo(started.associations());
            assertThat(advanced.createdAt()).isEqualTo(T0);
            assertThat(advanced.lastModifiedAt()).isEqualTo(T0.plusSeconds(5));
        }

        @Test
        @DisplayName("a second advance keeps RUNNING and moves lastModifiedAt forward")
        void secondAdvanceStaysRunning() {
            SagaInstance started = startValidSaga();
            clock.advance(Duration.ofSeconds(5));
            lifecycle.advance(started.sagaId());
            clock.advance(Duration.ofSeconds(5));

            SagaInstance advancedAgain = lifecycle.advance(started.sagaId());

            assertThat(advancedAgain.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(advancedAgain.createdAt()).isEqualTo(T0);
            assertThat(advancedAgain.lastModifiedAt()).isEqualTo(T0.plusSeconds(10));
        }
    }

    @Nested
    @DisplayName("start initializes the step map")
    class StartSteps {

        @Test
        @DisplayName("every @SagaStep method of the saga type is NOT_RUN with no touched aggregates")
        void everyStepNotRun() {
            SagaInstance instance = startValidSaga();

            assertThat(instance.steps()).containsOnly(
                entry(START_STEP, StepRecord.notRun()),
                entry(END_STEP, StepRecord.notRun()));
        }

        @Test
        @DisplayName("outcome and failure are null")
        void outcomeAndFailureNull() {
            SagaInstance instance = startValidSaga();

            assertThat(instance.outcome()).isNull();
            assertThat(instance.failure()).isNull();
        }
    }

    @Nested
    @DisplayName("recordStepCompleted")
    class RecordStepCompleted {

        @Test
        @DisplayName("marks only the named step COMPLETED with its touched aggregates; status unchanged")
        void marksStepCompleted() {
            SagaInstance started = startValidSaga();
            clock.advance(Duration.ofSeconds(2));

            SagaInstance recorded = lifecycle.recordStepCompleted(started.sagaId(), START_STEP, List.of(ORDER_1));

            assertThat(recorded.status()).isEqualTo(SagaStatus.CREATED);
            assertThat(recorded.steps()).containsOnly(
                entry(START_STEP, StepRecord.completed(List.of(ORDER_1))),
                entry(END_STEP, StepRecord.notRun()));
            assertThat(recorded.steps().get(START_STEP).status()).isEqualTo(StepStatus.COMPLETED);
            assertThat(recorded.outcome()).isNull();
            assertThat(recorded.failure()).isNull();
            assertThat(recorded.lastModifiedAt()).isEqualTo(T0.plusSeconds(2));
        }

        @Test
        @DisplayName("keeps a RUNNING instance RUNNING")
        void keepsRunning() {
            SagaInstance started = startValidSaga();
            lifecycle.advance(started.sagaId());

            SagaInstance recorded = lifecycle.recordStepCompleted(started.sagaId(), END_STEP, List.of());

            assertThat(recorded.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(recorded.steps().get(END_STEP)).isEqualTo(StepRecord.completed(List.of()));
        }

        @Test
        @DisplayName("touched aggregates are deduplicated in recording order")
        void deduplicatesTouchedAggregates() {
            SagaInstance started = startValidSaga();

            SagaInstance recorded = lifecycle.recordStepCompleted(
                started.sagaId(), START_STEP, List.of(ORDER_1, PAYMENT_1, ORDER_1));

            assertThat(recorded.steps().get(START_STEP).touchedAggregates()).containsExactly(ORDER_1, PAYMENT_1);
        }

        @Test
        @DisplayName("an unknown step name is rejected with IllegalArgumentException")
        void unknownStepRejected() {
            SagaInstance started = startValidSaga();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.recordStepCompleted(started.sagaId(), "noSuchStep", List.of()));
        }
    }

    @Nested
    @DisplayName("complete on the @EndSaga step (scenario 3)")
    class Complete {

        @Test
        @DisplayName("completing a RUNNING instance returns COMPLETED with the outcome step")
        void completeFromRunning() {
            SagaInstance started = startValidSaga();
            clock.advance(Duration.ofSeconds(5));
            lifecycle.advance(started.sagaId());
            clock.advance(Duration.ofSeconds(5));

            // @EndSaga verification is the caller's responsibility per the SagaLifecycle Javadoc
            SagaInstance completed = lifecycle.complete(started.sagaId(), END_STEP);

            assertThat(completed.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(completed.outcome()).isEqualTo(END_STEP);
            assertThat(completed.failure()).isNull();
            assertThat(completed.sagaId()).isEqualTo(started.sagaId());
            assertThat(completed.createdAt()).isEqualTo(T0);
            assertThat(completed.lastModifiedAt()).isEqualTo(T0.plusSeconds(10));
        }

        @Test
        @DisplayName("a saga finishing in its first unit of work completes directly from CREATED")
        void completeFromCreated() {
            SagaInstance started = startValidSaga();
            clock.advance(Duration.ofSeconds(3));

            SagaInstance completed = lifecycle.complete(started.sagaId(), END_STEP);

            assertThat(completed.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(completed.outcome()).isEqualTo(END_STEP);
            assertThat(completed.sagaId()).isEqualTo(started.sagaId());
            assertThat(completed.lastModifiedAt()).isEqualTo(T0.plusSeconds(3));
        }

        @Test
        @DisplayName("complete leaves the recorded steps unchanged")
        void completeKeepsSteps() {
            SagaInstance started = startValidSaga();
            SagaInstance recorded = lifecycle.recordStepCompleted(started.sagaId(), END_STEP, List.of(ORDER_1));

            SagaInstance completed = lifecycle.complete(started.sagaId(), END_STEP);

            assertThat(completed.steps()).isEqualTo(recorded.steps());
        }
    }

    @Nested
    @DisplayName("fail on a rolled-back unit of work")
    class Fail {

        @Test
        @DisplayName("failing a CREATED instance returns FAILED with the failure and unchanged steps")
        void failFromCreated() {
            SagaInstance started = startValidSaga();
            clock.advance(Duration.ofSeconds(4));

            SagaInstance failed = lifecycle.fail(started.sagaId(), failure());

            assertThat(failed.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(failed.failure()).isEqualTo(failure());
            assertThat(failed.outcome()).isNull();
            assertThat(failed.steps()).isEqualTo(started.steps());
            assertThat(failed.lastModifiedAt()).isEqualTo(T0.plusSeconds(4));
        }

        @Test
        @DisplayName("failing a RUNNING instance returns FAILED and keeps recorded steps")
        void failFromRunning() {
            SagaInstance started = startValidSaga();
            SagaInstance recorded = lifecycle.recordStepCompleted(started.sagaId(), START_STEP, List.of(ORDER_1));
            lifecycle.advance(started.sagaId());

            SagaInstance failed = lifecycle.fail(started.sagaId(), failure());

            assertThat(failed.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(failed.failure()).isEqualTo(failure());
            assertThat(failed.steps()).isEqualTo(recorded.steps());
        }

        @Test
        @DisplayName("same correlation can start again after the first instance FAILED")
        void sameCorrelationAfterFailureAllowed() {
            SagaInstance first = startValidSaga();
            lifecycle.fail(first.sagaId(), failure());

            SagaInstance second = startValidSaga();

            assertThat(second.status()).isEqualTo(SagaStatus.CREATED);
            assertThat(second.sagaId()).isNotEqualTo(first.sagaId());
        }
    }

    /**
     * Invokes the sagaId-addressed lifecycle operation named {@code operation} ("recordStepCompleted",
     * "advance", "complete" or "fail") with otherwise valid arguments.
     */
    private SagaInstance invoke(String operation, String sagaId) {
        return switch (operation) {
            case "recordStepCompleted" -> lifecycle.recordStepCompleted(sagaId, START_STEP, List.of(ORDER_1));
            case "advance" -> lifecycle.advance(sagaId);
            case "complete" -> lifecycle.complete(sagaId, END_STEP);
            case "fail" -> lifecycle.fail(sagaId, failure());
            default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    }

    private static SagaFailure failure() {
        return new SagaFailure(START_STEP, IllegalStateException.class.getName(), "boom", T0);
    }

    @Nested
    @DisplayName("transitions on a terminal instance (scenario 4)")
    class TerminalInstance {

        @ParameterizedTest(name = "{0} on a COMPLETED instance is rejected")
        @ValueSource(strings = {"recordStepCompleted", "advance", "complete", "fail"})
        void transitionOnCompletedInstanceThrows(String operation) {
            SagaInstance started = startValidSaga();
            lifecycle.advance(started.sagaId());
            lifecycle.complete(started.sagaId(), END_STEP);

            assertTerminalRejection(operation, started.sagaId(), SagaStatus.COMPLETED);
        }

        @ParameterizedTest(name = "{0} on a FAILED instance is rejected")
        @ValueSource(strings = {"recordStepCompleted", "advance", "complete", "fail"})
        void transitionOnFailedInstanceThrows(String operation) {
            SagaInstance started = startValidSaga();
            lifecycle.fail(started.sagaId(), failure());

            assertTerminalRejection(operation, started.sagaId(), SagaStatus.FAILED);
        }

        private void assertTerminalRejection(String operation, String sagaId, SagaStatus terminalStatus) {
            assertThatThrownBy(() -> invoke(operation, sagaId))
                .isInstanceOfSatisfying(InvalidSagaTransitionException.class, ex -> {
                    assertThat(ex.isNotFound()).isFalse();
                    assertThat(ex.getCurrentStatus()).contains(terminalStatus);
                    assertThat(ex.getOperation()).isEqualTo(operation);
                    assertThat(ex.getSagaId()).isEqualTo(sagaId);
                });
        }
    }

    @Nested
    @DisplayName("transitions on an unknown saga id")
    class UnknownInstance {

        @ParameterizedTest(name = "{0} on an unknown sagaId is rejected as not found")
        @ValueSource(strings = {"recordStepCompleted", "advance", "complete", "fail"})
        void transitionOnUnknownInstanceThrows(String operation) {
            String unknownSagaId = "does-not-exist";

            assertThatThrownBy(() -> invoke(operation, unknownSagaId))
                .isInstanceOfSatisfying(InvalidSagaTransitionException.class, ex -> {
                    assertThat(ex.isNotFound()).isTrue();
                    assertThat(ex.getCurrentStatus()).isEmpty();
                    assertThat(ex.getOperation()).isEqualTo(operation);
                    assertThat(ex.getSagaId()).isEqualTo(unknownSagaId);
                });
        }
    }

    @Nested
    @DisplayName("duplicate start for an already-active correlation")
    class DuplicateStart {

        @Test
        @DisplayName("a second start while the first instance is CREATED is rejected")
        void duplicateWhileCreatedThrows() {
            startValidSaga();

            assertDuplicateStartRejected();
        }

        @Test
        @DisplayName("a second start while the first instance is RUNNING is rejected")
        void duplicateWhileRunningThrows() {
            SagaInstance started = startValidSaga();
            lifecycle.advance(started.sagaId());

            assertDuplicateStartRejected();
        }

        @Test
        @DisplayName("same saga type with a different association value is allowed")
        void sameTypeDifferentValueAllowed() {
            SagaInstance first = startValidSaga();

            SagaInstance second = lifecycle.start(ValidSaga.class, ASSOCIATION_PROPERTY, "order-2");

            assertThat(second.status()).isEqualTo(SagaStatus.CREATED);
            assertThat(second.sagaId()).isNotEqualTo(first.sagaId());
        }

        @Test
        @DisplayName("a different saga type with the same association value is allowed")
        void differentTypeSameValueAllowed() {
            SagaInstance first = startValidSaga();

            SagaInstance second = lifecycle.start(SagaWithContextFieldSaga.class, ASSOCIATION_PROPERTY, ASSOCIATION_VALUE);

            assertThat(second.status()).isEqualTo(SagaStatus.CREATED);
            assertThat(second.sagaType()).isEqualTo(SagaWithContextFieldSaga.class.getName());
            assertThat(second.sagaId()).isNotEqualTo(first.sagaId());
        }

        @Test
        @DisplayName("same type and value after the first instance COMPLETED is allowed")
        void sameCorrelationAfterCompletionAllowed() {
            SagaInstance first = startValidSaga();
            lifecycle.complete(first.sagaId(), END_STEP);

            SagaInstance second = startValidSaga();

            assertThat(second.status()).isEqualTo(SagaStatus.CREATED);
            assertThat(second.sagaId()).isNotEqualTo(first.sagaId());
        }

        private void assertDuplicateStartRejected() {
            assertThatThrownBy(SagaLifecycleTest.this::startValidSaga)
                .isInstanceOfSatisfying(DuplicateSagaInstanceException.class, ex -> {
                    assertThat(ex.getSagaType()).isEqualTo(ValidSaga.class);
                    assertThat(ex.getAssociationProperty()).isEqualTo(ASSOCIATION_PROPERTY);
                    assertThat(ex.getAssociationValue()).isEqualTo(ASSOCIATION_VALUE);
                });
        }
    }

    @Nested
    @DisplayName("null arguments are rejected with IllegalArgumentException")
    class NullArguments {

        @Test
        @DisplayName("start with a null sagaType")
        void startNullSagaType() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.start(null, ASSOCIATION_PROPERTY, ASSOCIATION_VALUE));
        }

        @Test
        @DisplayName("start with a null associationProperty")
        void startNullAssociationProperty() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.start(ValidSaga.class, null, ASSOCIATION_VALUE));
        }

        @Test
        @DisplayName("start with a null associationValue")
        void startNullAssociationValue() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.start(ValidSaga.class, ASSOCIATION_PROPERTY, null));
        }

        @ParameterizedTest(name = "{0} with a null sagaId")
        @ValueSource(strings = {"recordStepCompleted", "advance", "complete", "fail"})
        void transitionNullSagaId(String operation) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> invoke(operation, null));
        }

        @Test
        @DisplayName("recordStepCompleted with a null stepName")
        void recordStepCompletedNullStepName() {
            SagaInstance started = startValidSaga();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.recordStepCompleted(started.sagaId(), null, List.of()));
        }

        @Test
        @DisplayName("recordStepCompleted with null touchedAggregates")
        void recordStepCompletedNullTouchedAggregates() {
            SagaInstance started = startValidSaga();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.recordStepCompleted(started.sagaId(), START_STEP, null));
        }

        @Test
        @DisplayName("complete with a null outcomeStep")
        void completeNullOutcomeStep() {
            SagaInstance started = startValidSaga();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.complete(started.sagaId(), null));
        }

        @Test
        @DisplayName("fail with a null failure")
        void failNullFailure() {
            SagaInstance started = startValidSaga();

            assertThatIllegalArgumentException()
                .isThrownBy(() -> lifecycle.fail(started.sagaId(), null));
        }
    }

    /** Test-local clock whose instant can be moved forward explicitly. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("MutableClock is fixed to UTC");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
