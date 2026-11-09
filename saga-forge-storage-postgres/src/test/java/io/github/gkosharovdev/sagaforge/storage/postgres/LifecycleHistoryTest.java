package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;

/**
 * {@code saga_lifecycle_history} as written by {@link PostgresStorageProvider#save(SagaInstance, List)} from the
 * entries handed over, read only through direct SQL (003 spec US3, quickstart scenarios 7 to 9 and 14; FR-007 to
 * FR-009, FR-012, SC-003; 004 FR-023, FR-033, SC-010). Every {@link Instant} is truncated to microseconds, the
 * {@code timestamptz} precision ({@code .specify/memory/memory.md} G-003-05).
 */
@Tag("container")
class LifecycleHistoryTest {

    private static final String SAGA_ID = "s-1";
    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:15:30.123456789Z")
            .truncatedTo(ChronoUnit.MICROS);
    private static final Instant T1 = CREATED_AT.plusSeconds(10);
    private static final Instant T2 = CREATED_AT.plusSeconds(20);
    private static final Instant T3 = CREATED_AT.plusSeconds(30);
    private static final AggregateReference ORDER_42 = new AggregateReference("com.example.Order", "42");
    private static final AggregateReference PAYMENT_7 = new AggregateReference("com.example.Payment", "p-7");

    private PostgresStorageProvider provider;

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
        provider = new PostgresStorageProvider(PostgresTestSupport.newDataSource());
    }

    private static Map<String, StepRecord> steps(StepRecord a, StepRecord b, StepRecord c) {
        Map<String, StepRecord> steps = new LinkedHashMap<>();
        steps.put("A", a);
        steps.put("B", b);
        steps.put("C", c);
        return steps;
    }

    private static SagaInstance instance(SagaStatus status, Instant modifiedAt, Map<String, StepRecord> steps,
                                         String outcome, SagaFailure failure) {
        return new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"), status, CREATED_AT,
                modifiedAt, steps, outcome, failure);
    }

    private static SagaLifecycleEntry created() {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.CREATED, null, List.of(), null, null,
                CREATED_AT);
    }

    private static SagaLifecycleEntry stepCompleted(String step, List<AggregateReference> touched, Instant at) {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.STEP_COMPLETED, step, touched, null, null, at);
    }

    private static SagaLifecycleEntry completed(String outcomeStep, Instant at) {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.COMPLETED, null, List.of(), outcomeStep, null,
                at);
    }

    private static SagaLifecycleEntry failed(SagaFailure failure, Instant at) {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.FAILED, null, List.of(), null, failure, at);
    }

    private static List<Map<String, Object>> history(String sagaId) {
        return PostgresTestSupport.queryRows(
                "SELECT * FROM saga_lifecycle_history WHERE saga_id = ? ORDER BY sequence_number", sagaId);
    }

    private static List<AggregateReference> touched(Map<String, Object> row) {
        return SagaInstanceRows.decodeTouchedAggregates("touched_aggregates", (String) row.get("touched_aggregates"));
    }

    @Test
    @DisplayName("scenario 7: CREATED, STEP_COMPLETED(A), STEP_COMPLETED(B), COMPLETED in sequence order (SC-003)")
    void fullLifecycleInOrder() {
        StepRecord aDone = StepRecord.completed(List.of(ORDER_42));
        StepRecord bDone = StepRecord.completed(List.of(PAYMENT_7));
        provider.save(instance(SagaStatus.CREATED, CREATED_AT,
                steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun()), null, null), List.of(created()));
        provider.save(instance(SagaStatus.RUNNING, T1, steps(aDone, StepRecord.notRun(), StepRecord.notRun()),
                null, null), List.of(stepCompleted("A", List.of(ORDER_42), T1)));
        provider.save(instance(SagaStatus.RUNNING, T2, steps(aDone, bDone, StepRecord.notRun()), null, null),
                List.of(stepCompleted("B", List.of(PAYMENT_7), T2)));
        provider.save(instance(SagaStatus.COMPLETED, T3, steps(aDone, bDone, StepRecord.notRun()), "B", null),
                List.of(completed("B", T3)));

        List<Map<String, Object>> rows = history(SAGA_ID);

        assertThat(rows).extracting(row -> row.get("sequence_number")).containsExactly(0L, 1L, 2L, 3L);
        assertThat(rows).extracting(row -> row.get("kind"))
                .containsExactly("CREATED", "STEP_COMPLETED", "STEP_COMPLETED", "COMPLETED");
        assertThat(rows).extracting(row -> row.get("step_name")).containsExactly(null, "A", "B", null);
        assertThat(rows).extracting(row -> row.get("occurred_at")).containsExactly(CREATED_AT, T1, T2, T3);
        assertThat(rows).extracting(row -> row.get("outcome_step")).containsExactly(null, null, null, "B");
        assertThat(touched(rows.get(0))).isEmpty();
        assertThat(touched(rows.get(1))).containsExactly(ORDER_42);
        assertThat(touched(rows.get(2))).containsExactly(PAYMENT_7);
        assertThat(touched(rows.get(3))).isEmpty();
    }

    @Test
    @DisplayName("scenario 8: a save that leaves step C NOT_RUN gives no row for C (FR-009)")
    void notRunStepHasNoRow() {
        provider.save(instance(SagaStatus.CREATED, CREATED_AT,
                steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun()), null, null), List.of(created()));
        provider.save(instance(SagaStatus.RUNNING, T2,
                steps(StepRecord.completed(List.of()), StepRecord.notRun(), StepRecord.notRun()), null, null),
                List.of(stepCompleted("A", List.of(), T2)));

        assertThat(history(SAGA_ID)).extracting(row -> row.get("step_name")).containsExactly(null, "A");
        assertThat(PostgresTestSupport.queryRows(
                "SELECT 1 FROM saga_lifecycle_history WHERE saga_id = ? AND step_name = 'C'", SAGA_ID)).isEmpty();
    }

    @Test
    @DisplayName("scenario 9: the FAILED row's failure_* columns equal findById(sagaId).failure()")
    void failedRowMatchesInstanceFailure() {
        SagaFailure failure = new SagaFailure("B", "java.lang.IllegalStateException", "declined", T2);
        provider.save(instance(SagaStatus.CREATED, CREATED_AT,
                steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun()), null, null), List.of(created()));
        SagaInstance failedInstance = instance(SagaStatus.FAILED, T2,
                steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun()), null, failure);
        provider.save(failedInstance, List.of(failed(failedInstance.failure(), T2)));

        List<Map<String, Object>> rows = history(SAGA_ID);
        Map<String, Object> failed = rows.get(rows.size() - 1);
        SagaFailure stored = provider.findById(SAGA_ID).orElseThrow().failure();

        assertThat(failed.get("kind")).isEqualTo("FAILED");
        assertThat(failed.get("sequence_number")).isEqualTo(1L);
        assertThat(new SagaFailure((String) failed.get("failure_step_name"), (String) failed.get("failure_cause_type"),
                (String) failed.get("failure_message"), (Instant) failed.get("failure_failed_at")))
                .isEqualTo(stored)
                .isEqualTo(failure);
    }

    @Test
    @DisplayName("a save without entries adds no row, even when steps completed (no derivation, FR-023)")
    void saveWithoutEntriesAddsNoRow() {
        provider.save(instance(SagaStatus.CREATED, CREATED_AT,
                steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun()), null, null), List.of(created()));
        provider.save(instance(SagaStatus.RUNNING, T1,
                steps(StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun(), StepRecord.notRun()), null, null));

        assertThat(history(SAGA_ID)).extracting(row -> row.get("kind")).containsExactly("CREATED");
    }

    @Test
    @DisplayName("zeta then alpha handed over in one save are stored zeta, alpha (hand-over order, SC-010)")
    void oneSaveKeepsHandOverOrder() {
        Map<String, StepRecord> before = new LinkedHashMap<>();
        before.put("zeta", StepRecord.notRun());
        before.put("alpha", StepRecord.notRun());
        Map<String, StepRecord> after = new LinkedHashMap<>();
        after.put("zeta", StepRecord.completed(List.of()));
        after.put("alpha", StepRecord.completed(List.of()));
        provider.save(new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of(), SagaStatus.CREATED, CREATED_AT,
                CREATED_AT, before, null, null), List.of(created()));
        provider.save(new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of(), SagaStatus.RUNNING, CREATED_AT, T2,
                after, null, null),
                List.of(stepCompleted("zeta", List.of(), T2), stepCompleted("alpha", List.of(), T2)));

        assertThat(history(SAGA_ID)).extracting(row -> row.get("step_name")).containsExactly(null, "zeta", "alpha");
    }

    @Test
    @DisplayName("a second save's entries are numbered after the first save's")
    void entriesAppendAfterExistingHistory() {
        StepRecord aDone = StepRecord.completed(List.of(ORDER_42));
        provider.save(instance(SagaStatus.CREATED, CREATED_AT,
                steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun()), null, null), List.of(created()));
        provider.save(instance(SagaStatus.COMPLETED, T1, steps(aDone, StepRecord.notRun(), StepRecord.notRun()),
                "A", null), List.of(stepCompleted("A", List.of(ORDER_42), T1), completed("A", T1)));

        List<Map<String, Object>> rows = history(SAGA_ID);

        assertThat(rows).extracting(row -> row.get("sequence_number")).containsExactly(0L, 1L, 2L);
        assertThat(rows).extracting(row -> row.get("kind"))
                .containsExactly("CREATED", "STEP_COMPLETED", "COMPLETED");
    }

    @Test
    @DisplayName("scenario 14 (history part): delete(sagaId) removes the history rows")
    void deleteRemovesHistory() {
        provider.save(instance(SagaStatus.CREATED, CREATED_AT,
                steps(StepRecord.notRun(), StepRecord.notRun(), StepRecord.notRun()), null, null), List.of(created()));
        provider.save(instance(SagaStatus.RUNNING, T2,
                steps(StepRecord.completed(List.of()), StepRecord.notRun(), StepRecord.notRun()), null, null),
                List.of(stepCompleted("A", List.of(), T2)));
        assertThat(history(SAGA_ID)).hasSize(2);

        provider.delete(SAGA_ID);

        assertThat(history(SAGA_ID)).isEmpty();
    }
}
