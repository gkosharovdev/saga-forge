package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepCompensation;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The 006 storage delta without a database (specs/006-saga-compensation contracts/storage-delta.md, tasks.md T049):
 * the codec of the compensation trigger, the completion sequence and the step compensation states, and the
 * {@code ForwardTransition} rules that keep them moving forward.
 */
class CompensationPersistenceTest {

    private static final Instant AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final SagaFailure FINAL = new SagaFailure("pay", "com.example.Refused", "no", AT,
            FailureClassification.FINAL, 1);
    private static final SagaFailure EXHAUSTED = new SagaFailure("book", "com.example.Busy", null, AT,
            FailureClassification.EXHAUSTED, 3);
    private static final CompensationTrigger REJECTED =
            CompensationTrigger.failureOutcome("reject", "com.example.DeliveryRejected", AT);

    static Stream<CompensationTrigger> triggers() {
        return Stream.of(REJECTED, CompensationTrigger.failed(FINAL, AT), CompensationTrigger.failed(EXHAUSTED, AT));
    }

    static Stream<StepCompensation> compensations() {
        return Stream.of(StepCompensation.notAttempted(), StepCompensation.compensated(AT),
                StepCompensation.nothingToCompensate(AT), StepCompensation.failed(FINAL),
                StepCompensation.failed(EXHAUSTED));
    }

    private static StepRecord completed(int sequence, StepCompensation compensation) {
        return new StepRecord(StepStatus.COMPLETED, List.of(), 1, List.of(), sequence, compensation);
    }

    private static SagaInstance instance(SagaStatus status, Map<String, StepRecord> steps,
            CompensationTrigger trigger) {
        return new SagaInstance("s-1", "com.example.Saga", Map.of(), Map.of("orderId", "o-1"), status, AT, AT, steps,
                null, null, null, trigger);
    }

    @ParameterizedTest
    @MethodSource("triggers")
    @DisplayName("every trigger kind round-trips through compensation_trigger")
    void triggerRoundTrip(CompensationTrigger trigger) {
        String json = SagaInstanceRows.encodeTrigger(SagaInstanceRows.TRIGGER_FIELD, trigger);

        assertThat(SagaInstanceRows.decodeTrigger(SagaInstanceRows.TRIGGER_FIELD, json)).isEqualTo(trigger);
    }

    @Test
    @DisplayName("no trigger is SQL NULL")
    void noTriggerIsNull() {
        assertThat(SagaInstanceRows.encodeTrigger(SagaInstanceRows.TRIGGER_FIELD, null)).isNull();
        assertThat(SagaInstanceRows.decodeTrigger(SagaInstanceRows.TRIGGER_FIELD, null)).isNull();
    }

    @ParameterizedTest
    @MethodSource("compensations")
    @DisplayName("every compensation state and the sequence round-trip through the steps JSON")
    void stepRoundTrip(StepCompensation compensation) {
        Map<String, StepRecord> steps = Map.of("pay", completed(4, compensation), "end", StepRecord.notRun());

        Map<String, StepRecord> decoded = SagaInstanceRows.decodeSteps(SagaInstanceRows.encodeSteps(steps));

        assertThat(decoded).isEqualTo(steps);
    }

    @Test
    @DisplayName("a completed step without a sequence (pre-006 JSON) is rejected with the upgrade hint")
    void completedWithoutSequenceRejected() {
        String json = "{\"pay\": {\"status\": \"COMPLETED\", \"touchedAggregates\": []}}";

        assertThatThrownBy(() -> SagaInstanceRows.decodeSteps(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holds no sequence")
                .hasMessageContaining("schema-upgrade-006.sql");
    }

    @Test
    @DisplayName("a NOT_RUN step without a sequence decodes to 0 and no compensation")
    void notRunWithoutSequence() {
        StepRecord decoded = SagaInstanceRows.decodeSteps("{\"end\": {\"status\": \"NOT_RUN\", "
                + "\"touchedAggregates\": []}}").get("end");

        assertThat(decoded.completionSequence()).isZero();
        assertThat(decoded.compensation()).isNull();
    }

    @Test
    @DisplayName("a forward move of the compensation states is accepted")
    void forwardMovesAccepted() {
        SagaInstance running = instance(SagaStatus.RUNNING, Map.of("pay", completed(1, null)), null);
        SagaInstance compensating = instance(SagaStatus.COMPENSATING,
                Map.of("pay", completed(1, StepCompensation.notAttempted())), REJECTED);
        SagaInstance compensated = instance(SagaStatus.COMPENSATED,
                Map.of("pay", completed(1, StepCompensation.compensated(AT))), REJECTED);
        SagaInstance intervention = instance(SagaStatus.REQUIRES_INTERVENTION,
                Map.of("pay", completed(1, StepCompensation.failed(FINAL))), REJECTED);

        assertThatCode(() -> {
            ForwardTransition.requireForwardTransition(running, compensating);
            ForwardTransition.requireForwardTransition(compensating, compensated);
            ForwardTransition.requireForwardTransition(compensating, intervention);
            ForwardTransition.requireForwardTransition(compensated, compensated);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a changed completion sequence is rejected")
    void sequenceChangeRejected() {
        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(
                instance(SagaStatus.RUNNING, Map.of("pay", completed(1, null)), null),
                instance(SagaStatus.RUNNING, Map.of("pay", completed(2, null)), null)))
                .isInstanceOf(ConcurrentSagaUpdateException.class)
                .hasMessageContaining("completionSequence");
    }

    @Test
    @DisplayName("a backwards or sideways compensation move is rejected")
    void backwardsCompensationRejected() {
        SagaInstance compensated = instance(SagaStatus.COMPENSATING, Map.of(
                "pay", completed(1, StepCompensation.compensated(AT)),
                "ship", completed(2, StepCompensation.notAttempted())), REJECTED);
        SagaInstance undone = instance(SagaStatus.COMPENSATING, Map.of(
                "pay", completed(1, StepCompensation.notAttempted()),
                "ship", completed(2, StepCompensation.notAttempted())), REJECTED);

        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(compensated, undone))
                .isInstanceOf(ConcurrentSagaUpdateException.class)
                .hasMessageContaining("recorded compensation COMPENSATED");
        SagaInstance nothing = instance(SagaStatus.COMPENSATING,
                Map.of("pay", completed(1, StepCompensation.nothingToCompensate(AT))), REJECTED);
        SagaInstance nowAttempted = instance(SagaStatus.COMPENSATING,
                Map.of("pay", completed(1, StepCompensation.notAttempted())), REJECTED);
        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(nothing, nowAttempted))
                .isInstanceOf(ConcurrentSagaUpdateException.class);
    }

    @Test
    @DisplayName("a changed trigger is rejected")
    void triggerChangeRejected() {
        SagaInstance previous = instance(SagaStatus.COMPENSATING, Map.of(), REJECTED);
        SagaInstance incoming = instance(SagaStatus.COMPENSATING, Map.of(),
                CompensationTrigger.failureOutcome("other", "com.example.Other", AT));

        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(previous, incoming))
                .isInstanceOf(ConcurrentSagaUpdateException.class)
                .hasMessageContaining("compensation trigger");
    }

    @Test
    @DisplayName("COMPENSATING only moves to COMPENSATED or REQUIRES_INTERVENTION; both are terminal")
    void statusRules() {
        SagaInstance compensating = instance(SagaStatus.COMPENSATING, Map.of(), REJECTED);
        SagaInstance running = new SagaInstance("s-1", "com.example.Saga", Map.of(), Map.of("orderId", "o-1"),
                SagaStatus.RUNNING, AT, AT, Map.of(), null, null, null, null);

        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(compensating, running))
                .isInstanceOf(ConcurrentSagaUpdateException.class);
        SagaInstance compensated = instance(SagaStatus.COMPENSATED, Map.of(), REJECTED);
        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(compensated, compensating))
                .isInstanceOf(ConcurrentSagaUpdateException.class)
                .hasMessageContaining("status is terminal COMPENSATED");
    }
}
