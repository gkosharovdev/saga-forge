package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency control of {@link PostgresStorageProvider#save} against a real PostgreSQL: the {@code FOR UPDATE}
 * row lock plus the forward-transition check, and the first-save race check (spec US4-2, quickstart scenarios 11
 * and 12; FR-010, SC-004; contracts/concurrency-control.md).
 *
 * <p>The concurrent tests release two threads together with a {@link CyclicBarrier}, each with its own provider
 * on its own {@code DataSource}. They are deterministic whichever thread reaches the lock first: the second
 * writer either blocks on the lock and checks against the winner's committed row, or runs after the winner
 * committed; both see the same snapshot. Every {@link Instant} is truncated to microseconds
 * ({@code .specify/memory/memory.md} G-003-05).</p>
 */
@Tag("container")
class ConcurrencyControlTest {

    private static final String SAGA_ID = "s-1";
    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:15:30.123456789Z")
            .truncatedTo(ChronoUnit.MICROS);
    private static final Instant T1 = CREATED_AT.plusSeconds(10);
    private static final Instant T2 = CREATED_AT.plusSeconds(20);
    private static final AggregateReference ORDER_42 = new AggregateReference("com.example.Order", "42");
    private static final AggregateReference PAYMENT_7 = new AggregateReference("com.example.Payment", "p-7");
    private static final long TIMEOUT_SECONDS = 30;

    private PostgresStorageProvider provider;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.reset();
        provider = new PostgresStorageProvider(PostgresTestSupport.newDataSource());
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static SagaInstance instance(SagaStatus status, Instant modifiedAt, StepRecord a, StepRecord b,
                                         String outcome, SagaFailure failure) {
        return new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"), status, CREATED_AT,
                modifiedAt, Map.of("A", a, "B", b), outcome, failure);
    }

    private static SagaInstance running(Instant modifiedAt, StepRecord a, StepRecord b) {
        return instance(SagaStatus.RUNNING, modifiedAt, a, b, null, null);
    }

    private static SagaLifecycleEntry createdEntry() {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.CREATED, null, List.of(), null, null,
                CREATED_AT);
    }

    private static SagaLifecycleEntry stepCompleted(String step, AggregateReference touched) {
        return new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.STEP_COMPLETED, step, List.of(touched), null,
                null, T1);
    }

    /** One {@code save(snapshot, entries)} call of a concurrent pair. */
    private record Write(SagaInstance snapshot, List<SagaLifecycleEntry> entries) {
    }

    /** The shared {@code previous} both writers read: created, nothing completed. */
    private SagaInstance savedInitial() {
        SagaInstance initial = instance(SagaStatus.CREATED, CREATED_AT, StepRecord.notRun(), StepRecord.notRun(),
                null, null);
        provider.save(initial, List.of(createdEntry()));
        return initial;
    }

    private static List<Map<String, Object>> history() {
        return PostgresTestSupport.queryRows(
                "SELECT * FROM saga_lifecycle_history WHERE saga_id = ? ORDER BY sequence_number", SAGA_ID);
    }

    /** Runs {@code first} and {@code second} from two threads released together; one result per write. */
    private List<Throwable> saveConcurrently(Write first, Write second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<?>> futures = new ArrayList<>();
        for (Write write : List.of(first, second)) {
            PostgresStorageProvider own = new PostgresStorageProvider(PostgresTestSupport.newDataSource());
            futures.add(executor.submit(() -> {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                own.save(write.snapshot(), write.entries());
                return null;
            }));
        }
        List<Throwable> outcomes = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                outcomes.add(null);
            } catch (ExecutionException e) {
                outcomes.add(e.getCause());
            }
        }
        return outcomes;
    }

    @Test
    @DisplayName("a stale sequential write that drops a completed step is rejected; only the first write persists")
    void staleSequentialWriteRejected() {
        savedInitial();
        SagaInstance aCompleted = running(T1, StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun());
        SagaInstance staleBCompleted = running(T2, StepRecord.notRun(), StepRecord.completed(List.of(PAYMENT_7)));

        provider.save(aCompleted, List.of(stepCompleted("A", ORDER_42)));

        assertThatThrownBy(() -> provider.save(staleBCompleted, List.of(stepCompleted("B", PAYMENT_7))))
                .isInstanceOf(ConcurrentSagaUpdateException.class)
                .hasMessageContaining("A")
                .satisfies(e -> assertThat(((ConcurrentSagaUpdateException) e).sagaId()).isEqualTo(SAGA_ID));
        assertThat(provider.findById(SAGA_ID)).get().isEqualTo(aCompleted);
        assertThat(history()).extracting(row -> row.get("kind")).containsExactly("CREATED", "STEP_COMPLETED");
        assertThat(history()).extracting(row -> row.get("step_name")).containsExactly(null, "A");
    }

    @RepeatedTest(20)
    @DisplayName("scenario 11: of two diverging concurrent saves exactly one wins and the other is rejected")
    void divergingConcurrentSaves() throws Exception {
        savedInitial();
        SagaInstance aCompleted = running(T1, StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun());
        SagaInstance bCompleted = running(T1, StepRecord.notRun(), StepRecord.completed(List.of(PAYMENT_7)));

        List<Throwable> outcomes = saveConcurrently(new Write(aCompleted, List.of(stepCompleted("A", ORDER_42))),
                new Write(bCompleted, List.of(stepCompleted("B", PAYMENT_7))));

        assertThat(outcomes).filteredOn(outcome -> outcome == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome != null).singleElement()
                .isInstanceOf(ConcurrentSagaUpdateException.class);
        boolean aWon = outcomes.get(0) == null;
        SagaInstance winner = aWon ? aCompleted : bCompleted;
        assertThat(provider.findById(SAGA_ID)).get().isEqualTo(winner);
        assertThat(history()).extracting(row -> row.get("kind")).containsExactly("CREATED", "STEP_COMPLETED");
        assertThat(history()).extracting(row -> row.get("step_name")).containsExactly(null, aWon ? "A" : "B");
    }

    @RepeatedTest(20)
    @DisplayName("scenario 12: two identical concurrent saves without entries both succeed and add no history row")
    void identicalConcurrentSaves() throws Exception {
        savedInitial();
        SagaInstance aCompleted = running(T1, StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun());

        List<Throwable> outcomes = saveConcurrently(new Write(aCompleted, List.of()), new Write(aCompleted, List.of()));

        assertThat(outcomes).containsExactly(null, null);
        assertThat(provider.findById(SAGA_ID)).get().isEqualTo(aCompleted);
        assertThat(history()).extracting(row -> row.get("kind")).containsExactly("CREATED");
    }

    @Test
    @DisplayName("changing a terminal COMPLETED status is rejected")
    void terminalCompletedStatusIsFinal() {
        SagaInstance completed = instance(SagaStatus.COMPLETED, T1, StepRecord.completed(List.of(ORDER_42)),
                StepRecord.notRun(), "A", null);
        provider.save(completed);

        assertThatThrownBy(() -> provider.save(running(T2, StepRecord.completed(List.of(ORDER_42)),
                StepRecord.notRun()))).isInstanceOf(ConcurrentSagaUpdateException.class);
        assertThatThrownBy(() -> provider.save(instance(SagaStatus.FAILED, T2,
                StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun(), null,
                new SagaFailure("B", "java.lang.IllegalStateException", "boom", T2))))
                .isInstanceOf(ConcurrentSagaUpdateException.class);
        assertThat(provider.findById(SAGA_ID)).get().isEqualTo(completed);
    }

    @Test
    @DisplayName("changing a terminal FAILED status is rejected")
    void terminalFailedStatusIsFinal() {
        SagaInstance failed = instance(SagaStatus.FAILED, T1, StepRecord.notRun(), StepRecord.notRun(), null,
                new SagaFailure("A", "java.lang.IllegalStateException", null, T1));
        provider.save(failed);

        assertThatThrownBy(() -> provider.save(running(T2, StepRecord.notRun(), StepRecord.notRun())))
                .isInstanceOf(ConcurrentSagaUpdateException.class);
        assertThat(provider.findById(SAGA_ID)).get().isEqualTo(failed);
    }

    @Test
    @DisplayName("changing a completed step's touchedAggregates is rejected")
    void completedStepFactsAreFinal() {
        SagaInstance aCompleted = running(T1, StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun());
        provider.save(aCompleted);

        assertThatThrownBy(() -> provider.save(running(T2, StepRecord.completed(List.of(ORDER_42, PAYMENT_7)),
                StepRecord.notRun()))).isInstanceOf(ConcurrentSagaUpdateException.class);
        assertThat(provider.findById(SAGA_ID)).get().isEqualTo(aCompleted);
    }

    @RepeatedTest(20)
    @DisplayName("two concurrent diverging first saves of a new sagaId: one wins, the other is rejected")
    void concurrentFirstSaves() throws Exception {
        SagaInstance aCompleted = running(T1, StepRecord.completed(List.of(ORDER_42)), StepRecord.notRun());
        SagaInstance bCompleted = running(T1, StepRecord.notRun(), StepRecord.completed(List.of(PAYMENT_7)));

        List<Throwable> outcomes = saveConcurrently(new Write(aCompleted, List.of(createdEntry())),
                new Write(bCompleted, List.of(createdEntry())));

        assertThat(outcomes).filteredOn(outcome -> outcome == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome != null).singleElement()
                .isInstanceOf(ConcurrentSagaUpdateException.class);
        SagaInstance winner = outcomes.get(0) == null ? aCompleted : bCompleted;
        assertThat(provider.findById(SAGA_ID)).get().isEqualTo(winner);
        assertThat(history()).extracting(row -> row.get("kind")).containsExactly("CREATED");
    }
}
