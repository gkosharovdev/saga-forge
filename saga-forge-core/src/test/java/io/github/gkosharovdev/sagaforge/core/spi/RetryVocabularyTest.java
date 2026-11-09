package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;

/** The record changes of {@code specs/005-step-retry} (data-model.md "Changed core types"). */
class RetryVocabularyTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final CommandId CHARGE_1 = CommandId.of("saga-1", "charge", "com.example.Charge", 1);
    private static final CommandId CHARGE_2 = CommandId.of("saga-1", "charge", "com.example.Charge", 2);

    private static SagaFailure transientFailure(int attempts) {
        return new SagaFailure("charge", "java.io.IOException", "timeout", NOW, FailureClassification.TRANSIENT,
                attempts);
    }

    @Nested
    @DisplayName("SagaFailure")
    class SagaFailureTests {

        @Test
        @DisplayName("the 4-argument constructor and of(step, cause, at) default to UNCLASSIFIED and 1 attempt")
        void defaults() {
            assertThat(new SagaFailure("s", "t", null, NOW).classification())
                    .isEqualTo(FailureClassification.UNCLASSIFIED);
            SagaFailure failure = SagaFailure.of("s", new IllegalStateException("boom"), NOW);
            assertThat(failure.classification()).isEqualTo(FailureClassification.UNCLASSIFIED);
            assertThat(failure.attempts()).isEqualTo(1);
            assertThat(failure.causeType()).isEqualTo(IllegalStateException.class.getName());
        }

        @Test
        @DisplayName("of(step, cause, at, classification, attempts) records both")
        void classified() {
            SagaFailure failure = SagaFailure.of("s", new IllegalStateException(), NOW,
                    FailureClassification.EXHAUSTED, 3);
            assertThat(failure.classification()).isEqualTo(FailureClassification.EXHAUSTED);
            assertThat(failure.attempts()).isEqualTo(3);
            assertThat(failure.classifiedAs(FailureClassification.FINAL, 4))
                    .extracting(SagaFailure::classification, SagaFailure::attempts)
                    .containsExactly(FailureClassification.FINAL, 4);
        }

        @Test
        @DisplayName("a null classification or fewer than 1 attempt is rejected")
        void rejectsInvalid() {
            assertThatThrownBy(() -> new SagaFailure("s", "t", null, NOW, null, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("classification");
            assertThatThrownBy(() -> new SagaFailure("s", "t", null, NOW, FailureClassification.FINAL, 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attempts");
        }
    }

    @Nested
    @DisplayName("PendingRetry")
    class PendingRetryTests {

        @Test
        @DisplayName("accepts a transient last failure of an attempt before attemptsStarted")
        void valid() {
            assertThat(new PendingRetry("start", 3, transientFailure(2)).attemptsStarted()).isEqualTo(3);
            assertThat(new PendingRetry("start", 4, transientFailure(2)).attemptsStarted()).isEqualTo(4);
        }

        @Test
        @DisplayName("rejects a blank trigger step, attemptsStarted below 2, a missing, non-transient or mismatched "
                + "last failure")
        void rejectsInvalid() {
            assertThatThrownBy(() -> new PendingRetry(" ", 2, transientFailure(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("triggerStep");
            assertThatThrownBy(() -> new PendingRetry("start", 1, transientFailure(1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attemptsStarted");
            assertThatThrownBy(() -> new PendingRetry("start", 2, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lastFailure");
            assertThatThrownBy(() -> new PendingRetry("start", 2,
                    transientFailure(1).classifiedAs(FailureClassification.FINAL, 1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TRANSIENT");
            assertThatThrownBy(() -> new PendingRetry("start", 2, transientFailure(2)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("below attemptsStarted");
        }
    }

    @Nested
    @DisplayName("StepRecord attempts and handledCommands")
    class StepRecordTests {

        @Test
        @DisplayName("the 2-argument forms default to attempt 1 when completed, 0 otherwise, and no commands")
        void defaults() {
            assertThat(StepRecord.notRun().attempts()).isZero();
            assertThat(StepRecord.completed(List.of()).attempts()).isEqualTo(1);
            assertThat(StepRecord.completed(List.of()).handledCommands()).isEmpty();
        }

        @Test
        @DisplayName("a completed record keeps attempts and commands in dispatch order, unmodifiable")
        void keeps() {
            StepRecord record = StepRecord.completed(List.of(), 2, List.of(CHARGE_2, CHARGE_1));
            assertThat(record.attempts()).isEqualTo(2);
            assertThat(record.handledCommands()).containsExactly(CHARGE_2, CHARGE_1);
            assertThatThrownBy(() -> record.handledCommands().add(CHARGE_1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("rejects commands or attempts on a step that is not completed, fewer than 1 attempt on a "
                + "completed one, duplicate, unscoped or null identities")
        void rejectsInvalid() {
            assertThatThrownBy(() -> new StepRecord(StepStatus.NOT_RUN, List.of(), 0, List.of(CHARGE_1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handledCommands");
            assertThatThrownBy(() -> new StepRecord(StepStatus.NOT_RUN, List.of(), 1, List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attempts must be 0");
            assertThatThrownBy(() -> StepRecord.completed(List.of(), 0, List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1");
            assertThatThrownBy(() -> StepRecord.completed(List.of(), 1, List.of(CHARGE_1, CHARGE_1)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicates");
            assertThatThrownBy(() -> StepRecord.completed(List.of(), 1,
                    List.of(CommandId.unscoped("com.example.Charge"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("saga-scoped");
            assertThatThrownBy(() -> StepRecord.completed(List.of(), 1, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handledCommands");
        }
    }

    @Nested
    @DisplayName("SagaInstance pendingRetry")
    class SagaInstanceTests {

        private SagaInstance instance(SagaStatus status, PendingRetry retry) {
            return new SagaInstance("saga-1", "com.example.Saga", Map.of(), Map.of(), status, NOW, NOW, Map.of(),
                    status == SagaStatus.COMPLETED ? "end" : null,
                    status == SagaStatus.FAILED ? new SagaFailure("s", "t", null, NOW) : null, retry);
        }

        @Test
        @DisplayName("is allowed while CREATED or RUNNING and absent by default")
        void allowedWhileActive() {
            PendingRetry retry = new PendingRetry("start", 2, transientFailure(1));
            assertThat(instance(SagaStatus.CREATED, retry).pendingRetry()).isEqualTo(retry);
            assertThat(instance(SagaStatus.RUNNING, retry).withPendingRetry(null).pendingRetry()).isNull();
            assertThat(new SagaInstance("saga-1", "t", Map.of(), Map.of(), SagaStatus.RUNNING, NOW, NOW, Map.of(),
                    null, null).pendingRetry()).isNull();
        }

        @Test
        @DisplayName("is rejected on a COMPLETED, FAILED or COMPENSATING instance")
        void rejectedOtherwise() {
            PendingRetry retry = new PendingRetry("start", 2, transientFailure(1));
            for (SagaStatus status : List.of(SagaStatus.COMPLETED, SagaStatus.FAILED, SagaStatus.COMPENSATING)) {
                assertThatThrownBy(() -> instance(status, retry))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pendingRetry");
            }
        }
    }

    @Nested
    @DisplayName("SagaLifecycleEntry ATTEMPT_FAILED")
    class AttemptFailedEntryTests {

        @Test
        @DisplayName("carries the failed step and its failure")
        void valid() {
            SagaLifecycleEntry entry = new SagaLifecycleEntry("saga-1", SagaLifecycleEventKind.ATTEMPT_FAILED,
                    "charge", List.of(), null, transientFailure(1), NOW);
            assertThat(entry.stepName()).isEqualTo("charge");
        }

        @Test
        @DisplayName("requires both the step name and the failure")
        void requiresBoth() {
            assertThatThrownBy(() -> new SagaLifecycleEntry("saga-1", SagaLifecycleEventKind.ATTEMPT_FAILED,
                    null, List.of(), null, transientFailure(1), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("stepName must be non-null if and only if kind is STEP_COMPLETED or "
                            + "ATTEMPT_FAILED");
            assertThatThrownBy(() -> new SagaLifecycleEntry("saga-1", SagaLifecycleEventKind.ATTEMPT_FAILED,
                    "charge", List.of(), null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("failure must be non-null if and only if kind is FAILED or ATTEMPT_FAILED");
        }
    }
}
