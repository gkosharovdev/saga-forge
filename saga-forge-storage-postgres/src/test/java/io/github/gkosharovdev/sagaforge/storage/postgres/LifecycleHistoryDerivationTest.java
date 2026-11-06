package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LifecycleHistoryDerivation#derive} against the data-model.md "Derivation algorithm" (no database).
 */
class LifecycleHistoryDerivationTest {

    private static final String SAGA_ID = "s-1";
    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant T1 = CREATED_AT.plusSeconds(10);
    private static final Instant T2 = CREATED_AT.plusSeconds(20);
    private static final AggregateReference ORDER_42 = new AggregateReference("com.example.Order", "42");
    private static final AggregateReference PAYMENT_7 = new AggregateReference("com.example.Payment", "p-7");
    private static final SagaFailure FAILURE =
            new SagaFailure("processPayment", "java.lang.IllegalStateException", "declined", T2);

    private static Map<String, StepRecord> steps(StepRecord a, StepRecord b, StepRecord c) {
        Map<String, StepRecord> steps = new LinkedHashMap<>();
        steps.put("alpha", a);
        steps.put("beta", b);
        steps.put("gamma", c);
        return steps;
    }

    private static Map<String, StepRecord> noneRun() {
        return steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun());
    }

    private static SagaInstance instance(SagaStatus status, Instant modifiedAt, Map<String, StepRecord> steps,
                                         String outcome, SagaFailure failure) {
        return new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"), status, CREATED_AT,
                modifiedAt, steps, outcome, failure);
    }

    private static SagaInstance running(Instant modifiedAt, Map<String, StepRecord> steps) {
        return instance(SagaStatus.RUNNING, modifiedAt, steps, null, null);
    }

    private static SagaLifecycleEntry created(long sequence) {
        return new SagaLifecycleEntry(SAGA_ID, sequence, SagaLifecycleEventKind.CREATED, null, List.of(), null,
                null, CREATED_AT);
    }

    private static SagaLifecycleEntry stepCompleted(long sequence, String step, List<AggregateReference> touched,
                                                    Instant at) {
        return new SagaLifecycleEntry(SAGA_ID, sequence, SagaLifecycleEventKind.STEP_COMPLETED, step, touched,
                null, null, at);
    }

    @Test
    @DisplayName("no previous snapshot: one CREATED entry at the given sequence number, occurredAt == createdAt")
    void firstSaveIsCreated() {
        SagaInstance first = instance(SagaStatus.CREATED, T1, noneRun(), null, null);

        assertThat(LifecycleHistoryDerivation.derive(null, first, 0)).containsExactly(created(0));
    }

    @Test
    @DisplayName("no previous snapshot, already COMPLETED: CREATED then COMPLETED with outcomeStep")
    void firstSaveTerminalCompleted() {
        SagaInstance first = instance(SagaStatus.COMPLETED, T1, noneRun(), "gamma", null);

        assertThat(LifecycleHistoryDerivation.derive(null, first, 0)).containsExactly(
                created(0),
                new SagaLifecycleEntry(SAGA_ID, 1, SagaLifecycleEventKind.COMPLETED, null, List.of(), "gamma", null,
                        T1));
    }

    @Test
    @DisplayName("no previous snapshot, already FAILED: CREATED then FAILED with failure")
    void firstSaveTerminalFailed() {
        SagaInstance first = instance(SagaStatus.FAILED, T1, noneRun(), null, FAILURE);

        assertThat(LifecycleHistoryDerivation.derive(null, first, 0)).containsExactly(
                created(0),
                new SagaLifecycleEntry(SAGA_ID, 1, SagaLifecycleEventKind.FAILED, null, List.of(), null, FAILURE,
                        T1));
    }

    @Test
    @DisplayName("one step NOT_RUN -> COMPLETED: one STEP_COMPLETED with its touchedAggregates at lastModifiedAt")
    void oneStepCompleted() {
        SagaInstance previous = running(T1, noneRun());
        SagaInstance incoming = running(T2,
                steps(StepRecord.notRun(), StepRecord.completed(List.of(ORDER_42, PAYMENT_7)), StepRecord.notRun()));

        assertThat(LifecycleHistoryDerivation.derive(previous, incoming, 1))
                .containsExactly(stepCompleted(1, "beta", List.of(ORDER_42, PAYMENT_7), T2));
    }

    @Test
    @DisplayName("two steps completed in one diff are ordered alphabetically by step name (research.md §5)")
    void twoStepsAlphabetical() {
        Map<String, StepRecord> before = new LinkedHashMap<>();
        before.put("zeta", StepRecord.notRun());
        before.put("alpha", StepRecord.notRun());
        Map<String, StepRecord> after = new LinkedHashMap<>();
        after.put("zeta", StepRecord.completed(List.of(PAYMENT_7)));
        after.put("alpha", StepRecord.completed(List.of(ORDER_42)));

        assertThat(LifecycleHistoryDerivation.derive(running(T1, before), running(T2, after), 4)).containsExactly(
                stepCompleted(4, "alpha", List.of(ORDER_42), T2),
                stepCompleted(5, "zeta", List.of(PAYMENT_7), T2));
    }

    @Test
    @DisplayName("status change to COMPLETED gives an entry with outcomeStep, after the step entries")
    void statusToCompleted() {
        SagaInstance previous = running(T1, steps(StepRecord.completed(List.of()), StepRecord.notRun(),
                StepRecord.notRun()));
        SagaInstance incoming = instance(SagaStatus.COMPLETED, T2, steps(StepRecord.completed(List.of()),
                StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun()), "beta", null);

        assertThat(LifecycleHistoryDerivation.derive(previous, incoming, 2)).containsExactly(
                stepCompleted(2, "beta", List.of(ORDER_42), T2),
                new SagaLifecycleEntry(SAGA_ID, 3, SagaLifecycleEventKind.COMPLETED, null, List.of(), "beta", null,
                        T2));
    }

    @Test
    @DisplayName("status change to FAILED gives an entry with failure")
    void statusToFailed() {
        SagaInstance previous = running(T1, noneRun());
        SagaInstance incoming = instance(SagaStatus.FAILED, T2, noneRun(), null, FAILURE);

        assertThat(LifecycleHistoryDerivation.derive(previous, incoming, 1)).containsExactly(
                new SagaLifecycleEntry(SAGA_ID, 1, SagaLifecycleEventKind.FAILED, null, List.of(), null, FAILURE,
                        T2));
    }

    @Test
    @DisplayName("CREATED -> RUNNING gives no entry")
    void createdToRunningIsSilent() {
        SagaInstance previous = instance(SagaStatus.CREATED, T1, noneRun(), null, null);

        assertThat(LifecycleHistoryDerivation.derive(previous, running(T2, noneRun()), 1)).isEmpty();
    }

    @Test
    @DisplayName("identical previous and incoming snapshots give zero entries (FR-009)")
    void identicalSnapshotsGiveNothing() {
        SagaInstance snapshot = instance(SagaStatus.COMPLETED, T2,
                steps(StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun(), StepRecord.notRun()), "alpha",
                null);

        assertThat(LifecycleHistoryDerivation.derive(snapshot, snapshot, 7)).isEmpty();
    }

    @Test
    @DisplayName("a step left NOT_RUN gives no entry")
    void notRunStepIsSilent() {
        SagaInstance previous = running(T1, noneRun());
        SagaInstance incoming = running(T2,
                steps(StepRecord.completed(List.of()), StepRecord.notRun(), StepRecord.notRun()));

        assertThat(LifecycleHistoryDerivation.derive(previous, incoming, 1))
                .extracting(SagaLifecycleEntry::stepName).containsExactly("alpha");
    }

    @Test
    @DisplayName("sequence numbers start at the given value and are consecutive")
    void sequenceNumbersConsecutive() {
        SagaInstance previous = running(T1, noneRun());
        SagaInstance incoming = instance(SagaStatus.COMPLETED, T2, steps(StepRecord.completed(List.of()),
                StepRecord.completed(List.of()), StepRecord.completed(List.of())), "gamma", null);

        assertThat(LifecycleHistoryDerivation.derive(previous, incoming, 10))
                .extracting(SagaLifecycleEntry::sequenceNumber).containsExactly(10L, 11L, 12L, 13L);
    }
}
