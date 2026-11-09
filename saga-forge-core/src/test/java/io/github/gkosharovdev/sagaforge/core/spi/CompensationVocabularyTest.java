package io.github.gkosharovdev.sagaforge.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The compensation vocabulary of {@code specs/006-saga-compensation} (data-model.md, tasks.md T013):
 * {@link CompensationTrigger}, {@link StepCompensation}, the new {@link StepRecord} and {@link SagaInstance}
 * components and invariants, and the new {@link SagaLifecycleEntry} rules.
 */
class CompensationVocabularyTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final String EVENT = "com.example.DeliveryRejectedEvent";

    private static SagaFailure failure(String step, FailureClassification classification) {
        return SagaFailure.of(step, new IllegalStateException("boom"), NOW, classification, 2);
    }

    private static StepRecord completed(int sequence, StepCompensation compensation) {
        return new StepRecord(StepStatus.COMPLETED, List.of(), 1, List.of(), sequence, compensation);
    }

    private static SagaInstance instance(SagaStatus status, Map<String, StepRecord> steps,
            CompensationTrigger trigger) {
        return new SagaInstance("saga-1", "com.example.Saga", Map.of(), Map.of(), status, NOW, NOW, steps,
                status == SagaStatus.COMPLETED ? "end" : null,
                status == SagaStatus.FAILED ? failure("a", FailureClassification.UNCLASSIFIED) : null, null, trigger);
    }

    private static final CompensationTrigger REJECTED = CompensationTrigger.failureOutcome("reject", EVENT, NOW);

    @Nested
    @DisplayName("CompensationTrigger")
    class TriggerTests {

        @Test
        @DisplayName("failureOutcome carries step and event type, no failure")
        void failureOutcome() {
            assertThat(REJECTED.kind()).isEqualTo(CompensationTriggerKind.FAILURE_OUTCOME);
            assertThat(REJECTED.eventType()).isEqualTo(EVENT);
            assertThat(REJECTED.failure()).isNull();
        }

        @Test
        @DisplayName("failed derives FINAL_FAILURE and RETRIES_EXHAUSTED from the classification")
        void failedDerivesKind() {
            assertThat(CompensationTrigger.failed(failure("pay", FailureClassification.FINAL), NOW).kind())
                    .isEqualTo(CompensationTriggerKind.FINAL_FAILURE);
            CompensationTrigger exhausted = CompensationTrigger.failed(failure("pay", FailureClassification.EXHAUSTED),
                    NOW);
            assertThat(exhausted.kind()).isEqualTo(CompensationTriggerKind.RETRIES_EXHAUSTED);
            assertThat(exhausted.stepName()).isEqualTo("pay");
            assertThat(exhausted.eventType()).isNull();
        }

        @ParameterizedTest
        @EnumSource(value = FailureClassification.class, names = {"TRANSIENT", "UNCLASSIFIED"})
        @DisplayName("failed rejects classifications that never start compensation")
        void failedRejects(FailureClassification classification) {
            assertThatThrownBy(() -> CompensationTrigger.failed(failure("pay", classification), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not start compensation");
        }

        @Test
        @DisplayName("the canonical constructor checks kind, event type, failure and their consistency")
        void canonicalRules() {
            assertThatThrownBy(() -> new CompensationTrigger(null, "s", EVENT, null, NOW))
                    .hasMessageContaining("kind");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FAILURE_OUTCOME, " ", EVENT, null,
                    NOW)).hasMessageContaining("stepName");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FAILURE_OUTCOME, "s", null, null,
                    NOW)).hasMessageContaining("eventType must be non-null if and only if");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FINAL_FAILURE, "pay", EVENT,
                    failure("pay", FailureClassification.FINAL), NOW))
                    .hasMessageContaining("eventType must be non-null if and only if");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FINAL_FAILURE, "pay", null, null,
                    NOW)).hasMessageContaining("failure must be non-null if and only if");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FINAL_FAILURE, "pay", null,
                    failure("pay", FailureClassification.EXHAUSTED), NOW))
                    .hasMessageContaining("must be classified FINAL");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.RETRIES_EXHAUSTED, "other", null,
                    failure("pay", FailureClassification.EXHAUSTED), NOW))
                    .hasMessageContaining("must be the failed step");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FAILURE_OUTCOME, "s", EVENT, null,
                    null)).hasMessageContaining("at");
        }
    }

    @Nested
    @DisplayName("StepCompensation")
    class StepCompensationTests {

        @Test
        @DisplayName("factories produce each status with the matching time and failure")
        void factories() {
            assertThat(StepCompensation.notAttempted()).extracting(StepCompensation::status, StepCompensation::at)
                    .containsExactly(CompensationStatus.NOT_ATTEMPTED, null);
            assertThat(StepCompensation.compensated(NOW).at()).isEqualTo(NOW);
            assertThat(StepCompensation.nothingToCompensate(NOW).status())
                    .isEqualTo(CompensationStatus.NOTHING_TO_COMPENSATE);
            SagaFailure refused = failure("refund", FailureClassification.FINAL);
            assertThat(StepCompensation.failed(refused)).extracting(StepCompensation::at, StepCompensation::failure)
                    .containsExactly(refused.failedAt(), refused);
        }

        @Test
        @DisplayName("time and failure must match the status, and a failure is never TRANSIENT")
        void rules() {
            assertThatThrownBy(() -> new StepCompensation(CompensationStatus.NOT_ATTEMPTED, NOW, null))
                    .hasMessageContaining("at must be null if and only if status is NOT_ATTEMPTED");
            assertThatThrownBy(() -> new StepCompensation(CompensationStatus.COMPENSATED, null, null))
                    .hasMessageContaining("at must be null if and only if status is NOT_ATTEMPTED");
            assertThatThrownBy(() -> new StepCompensation(CompensationStatus.FAILED, NOW, null))
                    .hasMessageContaining("failure must be non-null if and only if status is FAILED");
            assertThatThrownBy(() -> new StepCompensation(CompensationStatus.COMPENSATED, NOW,
                    failure("r", FailureClassification.FINAL)))
                    .hasMessageContaining("failure must be non-null if and only if status is FAILED");
            assertThatThrownBy(() -> StepCompensation.failed(failure("r", FailureClassification.TRANSIENT)))
                    .hasMessageContaining("FINAL, EXHAUSTED or UNCLASSIFIED");
            assertThatThrownBy(() -> new StepCompensation(null, NOW, null)).hasMessageContaining("status");
        }
    }

    @Nested
    @DisplayName("StepRecord")
    class StepRecordTests {

        @Test
        @DisplayName("compatibility constructors default the sequence to 1 when completed and leave no compensation")
        void compatibilityDefaults() {
            assertThat(StepRecord.completed(List.of())).extracting(StepRecord::completionSequence,
                    StepRecord::compensation).containsExactly(1, null);
            assertThat(StepRecord.notRun().completionSequence()).isZero();
            assertThat(StepRecord.completed(List.of(), 2, List.of(), 5).completionSequence()).isEqualTo(5);
        }

        @Test
        @DisplayName("the sequence and compensation follow the status")
        void rules() {
            assertThatThrownBy(() -> completed(0, null)).hasMessageContaining("completionSequence must be at least 1");
            assertThatThrownBy(() -> new StepRecord(StepStatus.NOT_RUN, List.of(), 0, List.of(), 1, null))
                    .hasMessageContaining("completionSequence must be 0");
            assertThatThrownBy(() -> new StepRecord(StepStatus.NOT_RUN, List.of(), 0, List.of(), 0,
                    StepCompensation.notAttempted()))
                    .hasMessageContaining("compensation must be null unless status is COMPLETED");
        }

        @Test
        @DisplayName("withers replace one component and keep the rest")
        void withers() {
            StepRecord step = completed(1, null).withCompletionSequence(3)
                    .withCompensation(StepCompensation.notAttempted());
            assertThat(step.completionSequence()).isEqualTo(3);
            assertThat(step.compensation()).isEqualTo(StepCompensation.notAttempted());
            assertThat(step.attempts()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("SagaInstance")
    class SagaInstanceTests {

        @Test
        @DisplayName("a trigger is present if and only if the status is a compensation status")
        void triggerIffCompensation() {
            for (SagaStatus status : SagaStatus.values()) {
                boolean compensation = SagaInstance.isCompensation(status);
                Map<String, StepRecord> steps = status == SagaStatus.REQUIRES_INTERVENTION
                        ? Map.of("a", completed(1, StepCompensation.failed(failure("a", FailureClassification.FINAL))))
                        : Map.of();
                if (compensation) {
                    assertThatCode(() -> instance(status, steps, REJECTED)).doesNotThrowAnyException();
                    assertThatThrownBy(() -> instance(status, steps, null))
                            .hasMessageContaining("compensationTrigger must be non-null if and only if");
                } else {
                    assertThatCode(() -> instance(status, steps, null)).doesNotThrowAnyException();
                    assertThatThrownBy(() -> instance(status, steps, REJECTED))
                            .hasMessageContaining("compensationTrigger must be non-null if and only if");
                }
            }
        }

        @Test
        @DisplayName("steps carry compensation states only while compensating or compensated")
        void compensationStatesOnlyInCompensation() {
            assertThatThrownBy(() -> instance(SagaStatus.RUNNING,
                    Map.of("a", completed(1, StepCompensation.notAttempted())), null))
                    .hasMessageContaining("must have no compensation state");
        }

        @Test
        @DisplayName("a COMPENSATED instance has no step not attempted or failed")
        void compensatedIsComplete() {
            assertThatCode(() -> instance(SagaStatus.COMPENSATED, Map.of(
                    "a", completed(1, StepCompensation.compensated(NOW)),
                    "b", completed(2, StepCompensation.nothingToCompensate(NOW))), REJECTED))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> instance(SagaStatus.COMPENSATED,
                    Map.of("a", completed(1, StepCompensation.notAttempted())), REJECTED))
                    .hasMessageContaining("of a COMPENSATED instance must not be NOT_ATTEMPTED");
        }

        @Test
        @DisplayName("exactly one failed compensation if and only if REQUIRES_INTERVENTION")
        void exactlyOneFailure() {
            StepCompensation failed = StepCompensation.failed(failure("b", FailureClassification.FINAL));
            assertThatCode(() -> instance(SagaStatus.REQUIRES_INTERVENTION, Map.of(
                    "a", completed(1, StepCompensation.notAttempted()),
                    "b", completed(2, failed)), REJECTED)).doesNotThrowAnyException();
            assertThatThrownBy(() -> instance(SagaStatus.REQUIRES_INTERVENTION,
                    Map.of("a", completed(1, StepCompensation.notAttempted())), REJECTED))
                    .hasMessageContaining("exactly one step must have a FAILED compensation");
            assertThatThrownBy(() -> instance(SagaStatus.REQUIRES_INTERVENTION, Map.of(
                    "a", completed(1, failed), "b", completed(2, failed)), REJECTED))
                    .hasMessageContaining("exactly one step must have a FAILED compensation");
            assertThatThrownBy(() -> instance(SagaStatus.COMPENSATING, Map.of("a", completed(1, failed)), REJECTED))
                    .hasMessageContaining("exactly one step must have a FAILED compensation");
        }

        @Test
        @DisplayName("withCompensationTrigger replaces the trigger; the 10- and 11-argument constructors leave none")
        void witherAndCompatibility() {
            SagaInstance running = new SagaInstance("saga-1", "t", Map.of(), Map.of(), SagaStatus.RUNNING, NOW, NOW,
                    Map.of(), null, null, null);
            assertThat(running.compensationTrigger()).isNull();
            SagaInstance compensating = new SagaInstance("saga-1", "t", Map.of(), Map.of(), SagaStatus.COMPENSATING,
                    NOW, NOW, Map.of(), null, null, null, REJECTED);
            assertThat(compensating.withCompensationTrigger(CompensationTrigger.failureOutcome("x", EVENT, NOW))
                    .compensationTrigger().stepName()).isEqualTo("x");
            assertThatThrownBy(() -> new SagaInstance("saga-1", "t", Map.of(), Map.of(), SagaStatus.COMPENSATING, NOW,
                    NOW, Map.of(), null, null, null, null).withPendingRetry(null))
                    .hasMessageContaining("compensationTrigger");
        }
    }

    @Nested
    @DisplayName("SagaLifecycleEntry")
    class EntryTests {

        private SagaLifecycleEntry entry(SagaLifecycleEventKind kind, String step, SagaFailure failure,
                CompensationTrigger trigger) {
            return new SagaLifecycleEntry("saga-1", kind, step, List.of(), null, failure, NOW, trigger);
        }

        @Test
        @DisplayName("each compensation kind accepts exactly its parts")
        void accepted() {
            SagaFailure refused = failure("pay", FailureClassification.FINAL);
            assertThatCode(() -> {
                entry(SagaLifecycleEventKind.COMPENSATION_STARTED, null, null, REJECTED);
                entry(SagaLifecycleEventKind.STEP_COMPENSATED, "pay", null, null);
                entry(SagaLifecycleEventKind.COMPENSATION_FAILED, "pay", refused, null);
                entry(SagaLifecycleEventKind.COMPENSATED, null, null, null);
                entry(SagaLifecycleEventKind.REQUIRES_INTERVENTION, null, null, null);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a missing or extra part is rejected")
        void rejected() {
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.COMPENSATION_STARTED, null, null, null))
                    .hasMessageContaining("trigger must be non-null if and only if kind is COMPENSATION_STARTED");
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.COMPENSATED, null, null, REJECTED))
                    .hasMessageContaining("trigger must be non-null if and only if kind is COMPENSATION_STARTED");
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.STEP_COMPENSATED, null, null, null))
                    .hasMessageContaining("stepName must be non-null");
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.COMPENSATION_FAILED, "pay", null, null))
                    .hasMessageContaining("failure must be non-null");
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.REQUIRES_INTERVENTION, "pay", null, null))
                    .hasMessageContaining("stepName must be non-null");
        }

        @Test
        @DisplayName("the 7-argument constructor leaves no trigger")
        void compatibility() {
            assertThat(new SagaLifecycleEntry("saga-1", SagaLifecycleEventKind.COMPENSATED, null, List.of(), null,
                    null, NOW).trigger()).isNull();
        }
    }
}
