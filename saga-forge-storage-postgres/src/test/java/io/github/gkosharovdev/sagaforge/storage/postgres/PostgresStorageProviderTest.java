package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * {@link PostgresStorageProvider} against a real PostgreSQL (spec US1, quickstart scenarios 1 to 3 and 13;
 * FR-001 to FR-004, FR-011, SC-001). Every {@link Instant} is truncated to microseconds, the {@code timestamptz}
 * precision ({@code .specify/memory/memory.md} G-003-05).
 */
@Tag("container")
class PostgresStorageProviderTest {

    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final String ORDER = "com.example.Order";
    private static final String PAYMENT = "com.example.Payment";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:15:30.123456789Z")
            .truncatedTo(ChronoUnit.MICROS);
    private static final Instant MODIFIED_AT = CREATED_AT.plusSeconds(42).plusNanos(987_000);

    private PostgresStorageProvider provider;

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
        provider = new PostgresStorageProvider(PostgresTestSupport.newDataSource());
    }

    private static SagaInstance running(String sagaId, Map<String, Object> state, Map<String, String> associations) {
        return new SagaInstance(sagaId, SAGA_TYPE, state, associations, SagaStatus.RUNNING, CREATED_AT, MODIFIED_AT,
                Map.of("handleOrderCreated", StepRecord.completed(List.of()), "processPayment", StepRecord.notRun()),
                null, null);
    }

    private static SagaInstance withAssociations(String sagaId, Map<String, String> associations) {
        return running(sagaId, Map.of(), associations);
    }

    private static SagaLifecycleEntry createdEntry(String sagaId) {
        return new SagaLifecycleEntry(sagaId, SagaLifecycleEventKind.CREATED, null, List.of(), null, null,
                CREATED_AT);
    }

    /** A new provider on a new {@code DataSource}: a simulated restart. */
    private static PostgresStorageProvider restarted() {
        return new PostgresStorageProvider(PostgresTestSupport.newDataSource());
    }

    @Nested
    @DisplayName("RoundTrip")
    class RoundTrip {

        @Test
        @DisplayName("scenario 1: a RUNNING instance with state, associations and step records survives a restart")
        void runningInstanceSurvivesRestart() {
            Map<String, Object> state = Map.of(
                    "customerName", "Ada \"Lovelace\" \\ Ünïcode",
                    "express", true,
                    "quantity", 3,
                    "totalMillis", 9_000_000_000L,
                    "discount", 0.15,
                    "shipping", Map.of("city", "Sofia", "floor", 2, "lines", List.of("street 1", "block 7")));
            Map<String, String> associations = Map.of("orderId", "o-1", "customerId", "c-1");
            SagaInstance saved = new SagaInstance("s-1", SAGA_TYPE, state, associations, SagaStatus.RUNNING,
                    CREATED_AT, MODIFIED_AT,
                    Map.of("handleOrderCreated", StepRecord.completed(List.of(
                                    new AggregateReference(ORDER, "42"), new AggregateReference(PAYMENT, "p-7"))),
                            "processPayment", StepRecord.notRun()),
                    null, null);

            provider.save(saved);

            assertThat(restarted().findById("s-1")).get().isEqualTo(saved);
        }

        @Test
        @DisplayName("005: attempts, handled command identities, a pending retry and a classified failure survive a"
                + " restart")
        void retryFieldsSurviveRestart() {
            io.github.gkosharovdev.sagaforge.core.command.CommandId charge =
                    io.github.gkosharovdev.sagaforge.core.command.CommandId.of("s-1", "handleOrderCreated",
                            "com.example.Charge", 1);
            SagaFailure attempt = new SagaFailure("processPayment", "com.example.CourierTimeout", "timed out",
                    MODIFIED_AT, io.github.gkosharovdev.sagaforge.core.failure.FailureClassification.TRANSIENT, 1);
            SagaInstance waiting = new SagaInstance("s-1", SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"),
                    SagaStatus.RUNNING, CREATED_AT, MODIFIED_AT,
                    Map.of("handleOrderCreated", StepRecord.completed(List.of(), 2, List.of(charge)),
                            "processPayment", StepRecord.notRun()),
                    null, null, new io.github.gkosharovdev.sagaforge.core.spi.PendingRetry("processPayment", 2,
                            attempt));
            provider.save(waiting);
            assertThat(restarted().findById("s-1")).get().isEqualTo(waiting);

            SagaInstance exhausted = new SagaInstance("s-1", SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"),
                    SagaStatus.FAILED, CREATED_AT, MODIFIED_AT, waiting.steps(), null,
                    attempt.classifiedAs(io.github.gkosharovdev.sagaforge.core.failure.FailureClassification.EXHAUSTED,
                            3));
            provider.save(exhausted);
            assertThat(restarted().findById("s-1")).get().isEqualTo(exhausted);
        }

        @Test
        @DisplayName("006: the trigger, completion sequences and compensation states survive a restart")
        void compensationFieldsSurviveRestart() {
            io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger trigger =
                    io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger.failureOutcome(
                            "rejectDelivery", "com.example.DeliveryRejected", MODIFIED_AT);
            StepRecord placed = new StepRecord(io.github.gkosharovdev.sagaforge.core.spi.StepStatus.COMPLETED,
                    List.of(), 1, List.of(), 1, null);
            StepRecord paid = new StepRecord(io.github.gkosharovdev.sagaforge.core.spi.StepStatus.COMPLETED,
                    List.of(), 1, List.of(), 2, null);
            SagaInstance running = new SagaInstance("s-1", SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"),
                    SagaStatus.RUNNING, CREATED_AT, MODIFIED_AT,
                    Map.of("handleOrderCreated", placed, "processPayment", paid), null, null, null, null);
            provider.save(running);
            SagaInstance compensating = new SagaInstance("s-1", SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"),
                    SagaStatus.COMPENSATING, CREATED_AT, MODIFIED_AT, Map.of(
                            "handleOrderCreated", placed.withCompensation(
                                    io.github.gkosharovdev.sagaforge.core.spi.StepCompensation.nothingToCompensate(
                                            MODIFIED_AT)),
                            "processPayment", paid.withCompensation(
                                    io.github.gkosharovdev.sagaforge.core.spi.StepCompensation.notAttempted())),
                    null, null, null, trigger);
            provider.save(compensating);
            assertThat(restarted().findById("s-1")).get().isEqualTo(compensating);

            SagaInstance compensated = new SagaInstance("s-1", SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"),
                    SagaStatus.COMPENSATED, CREATED_AT, MODIFIED_AT, Map.of(
                            "handleOrderCreated", compensating.steps().get("handleOrderCreated"),
                            "processPayment", paid.withCompensation(
                                    io.github.gkosharovdev.sagaforge.core.spi.StepCompensation.compensated(
                                            MODIFIED_AT))),
                    null, null, null, trigger);
            provider.save(compensated);
            assertThat(restarted().findById("s-1")).get().isEqualTo(compensated);
        }

        @Test
        @DisplayName("scenario 2: findById for an unknown id returns Optional.empty()")
        void unknownIdIsEmpty() {
            assertThat(provider.findById("missing")).isEmpty();
        }

        @Test
        @DisplayName("scenario 3: COMPLETED and FAILED instances round-trip with outcome and failure intact")
        void terminalInstancesRoundTrip() {
            Map<String, StepRecord> steps = Map.of(
                    "handleOrderCreated", StepRecord.completed(List.of(new AggregateReference(ORDER, "42"))),
                    "completeOrder", StepRecord.completed(List.of()));
            SagaInstance completed = new SagaInstance("s-completed", SAGA_TYPE, Map.of("k", "v"),
                    Map.of("orderId", "o-1"), SagaStatus.COMPLETED, CREATED_AT, MODIFIED_AT, steps,
                    "completeOrder", null);
            SagaInstance failedWithoutMessage = new SagaInstance("s-failed-1", SAGA_TYPE, Map.of(),
                    Map.of("orderId", "o-2"), SagaStatus.FAILED, CREATED_AT, MODIFIED_AT,
                    Map.of("processPayment", StepRecord.notRun()), null,
                    new SagaFailure("processPayment", "java.lang.IllegalStateException", null, MODIFIED_AT));
            SagaInstance failedWithMessage = new SagaInstance("s-failed-2", SAGA_TYPE, Map.of(),
                    Map.of("orderId", "o-3"), SagaStatus.FAILED, CREATED_AT, MODIFIED_AT,
                    Map.of("processPayment", StepRecord.notRun()), null,
                    new SagaFailure("processPayment", "com.example.PaymentDeclined", "card declined",
                            MODIFIED_AT.plusMillis(1)));

            provider.save(completed);
            provider.save(failedWithoutMessage);
            provider.save(failedWithMessage);

            PostgresStorageProvider afterRestart = restarted();
            assertThat(afterRestart.findById("s-completed")).get().isEqualTo(completed);
            assertThat(afterRestart.findById("s-failed-1")).get().isEqualTo(failedWithoutMessage);
            assertThat(afterRestart.findById("s-failed-2")).get().isEqualTo(failedWithMessage);
        }

        @Test
        @DisplayName("saving the same sagaId again replaces the row and its association rows")
        void resaveReplaces() {
            provider.save(new SagaInstance("s-1", SAGA_TYPE, Map.of("attempt", 1),
                    Map.of("orderId", "o-1", "customerId", "c-1"), SagaStatus.CREATED, CREATED_AT, CREATED_AT,
                    Map.of("processPayment", StepRecord.notRun()), null, null));
            SagaInstance replacement = new SagaInstance("s-1", SAGA_TYPE, Map.of("attempt", 2),
                    Map.of("orderId", "o-2"), SagaStatus.RUNNING, CREATED_AT, MODIFIED_AT,
                    Map.of("processPayment", StepRecord.notRun()), null, null);

            provider.save(replacement);

            assertThat(provider.findById("s-1")).get().isEqualTo(replacement);
            assertThat(provider.findByAssociation("customerId", "c-1")).isEmpty();
            assertThat(provider.findByAssociation("orderId", "o-1")).isEmpty();
            assertThat(provider.findByAssociation("orderId", "o-2")).containsExactly(replacement);
            assertThat(PostgresTestSupport.queryRows(
                    "SELECT association_property, association_value FROM saga_association WHERE saga_id = ?", "s-1"))
                    .containsExactly(Map.of("association_property", "orderId", "association_value", "o-2"));
        }

        @Test
        @DisplayName("findByAssociation matches on the exact property and value; scenario 13: no match is empty")
        void findByAssociation() {
            SagaInstance s1 = withAssociations("s1", Map.of("orderId", "o-1"));
            SagaInstance s4 = withAssociations("s4", Map.of("orderId", "o-1", "customerId", "c-9"));
            provider.save(s4);
            provider.save(withAssociations("s2", Map.of("orderId", "o-2")));
            provider.save(withAssociations("s3", Map.of("customerId", "o-1")));
            provider.save(s1);

            PostgresStorageProvider afterRestart = restarted();
            assertThat(afterRestart.findByAssociation("orderId", "o-1")).containsExactly(s1, s4);
            assertThat(afterRestart.findByAssociation("orderId", "o-9")).isNotNull().isEmpty();
            assertThat(afterRestart.findByAssociation("unknownProperty", "o-1")).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("delete removes the instance and its association rows; deleting an unknown id is a no-op")
        void delete() {
            provider.save(withAssociations("s-1", Map.of("orderId", "o-1")));
            provider.save(withAssociations("s-2", Map.of("orderId", "o-1")));

            provider.delete("s-1");
            provider.delete("never-saved");

            assertThat(provider.findById("s-1")).isEmpty();
            assertThat(provider.findByAssociation("orderId", "o-1")).extracting(SagaInstance::sagaId)
                    .containsExactly("s-2");
            assertThat(PostgresTestSupport.queryRows("SELECT * FROM saga_association WHERE saga_id = ?", "s-1"))
                    .isEmpty();
        }

        @Test
        @DisplayName("null arguments and a null DataSource are rejected with IllegalArgumentException")
        void nullRules() {
            assertThatThrownBy(() -> provider.save(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.findById(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.findByAssociation(null, "v"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.findByAssociation("p", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.delete(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PostgresStorageProvider(null)).isInstanceOf(IllegalArgumentException.class);

            SagaInstance instance = withAssociations("s-1", Map.of("orderId", "o-1"));
            List<SagaLifecycleEntry> withNull = Arrays.asList(createdEntry("s-1"), null);
            assertThatThrownBy(() -> provider.save(instance, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.save(instance, withNull)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.save(instance, List.of(createdEntry("s-other"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(PostgresTestSupport.queryRows("SELECT * FROM saga_instance")).isEmpty();
            assertThat(PostgresTestSupport.queryRows("SELECT * FROM saga_lifecycle_history")).isEmpty();
        }

        @Test
        @DisplayName("an instance with a null sagaId or status is rejected before any row is written")
        void nullInstanceFieldsRejected() {
            SagaInstance noId = new SagaInstance(null, SAGA_TYPE, Map.of(), Map.of(), SagaStatus.RUNNING,
                    CREATED_AT, MODIFIED_AT, Map.of(), null, null);
            SagaInstance noStatus = new SagaInstance("s-1", SAGA_TYPE, Map.of(), Map.of(), null,
                    CREATED_AT, MODIFIED_AT, Map.of(), null, null);

            assertThatThrownBy(() -> provider.save(noId)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sagaId");
            assertThatThrownBy(() -> provider.save(noStatus)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("status");
            assertThat(PostgresTestSupport.queryRows("SELECT * FROM saga_instance")).isEmpty();
        }
    }

    /**
     * Scenario 10 (spec US4-1, FR-008): a failure on the history insert rolls back the instance upsert and the
     * association replacement of the same {@code save()}. Guards the single-transaction guarantee of T019.
     */
    @Nested
    @DisplayName("Atomicity")
    class Atomicity {

        private SagaInstance created(String sagaId) {
            return new SagaInstance(sagaId, SAGA_TYPE, Map.of("attempt", 1), Map.of("orderId", "o-1"),
                    SagaStatus.CREATED, CREATED_AT, CREATED_AT,
                    Map.of("handleOrderCreated", StepRecord.notRun(), "processPayment", StepRecord.notRun()),
                    null, null);
        }

        private List<Map<String, Object>> history(String sagaId) {
            return PostgresTestSupport.queryRows(
                    "SELECT * FROM saga_lifecycle_history WHERE saga_id = ? ORDER BY sequence_number", sagaId);
        }

        @Test
        @DisplayName("scenario 10: a failed history insert leaves the pre-call snapshot and adds no history row")
        void failedHistoryInsertKeepsPreCallSnapshot() {
            SagaInstance before = created("s-1");
            provider.save(before, List.of(createdEntry("s-1")));
            SagaInstance stepCompleted = new SagaInstance("s-1", SAGA_TYPE, Map.of("attempt", 2),
                    Map.of("orderId", "o-1", "paymentId", "p-7"), SagaStatus.RUNNING, CREATED_AT, MODIFIED_AT,
                    Map.of("handleOrderCreated", StepRecord.completed(List.of(new AggregateReference(ORDER, "42"))),
                            "processPayment", StepRecord.notRun()),
                    null, null);
            List<SagaLifecycleEntry> entries = List.of(new SagaLifecycleEntry("s-1",
                    SagaLifecycleEventKind.STEP_COMPLETED, "handleOrderCreated",
                    List.of(new AggregateReference(ORDER, "42")), null, null, MODIFIED_AT));
            PostgresTestSupport.failOn("saga_lifecycle_history", "INSERT");

            assertThatThrownBy(() -> provider.save(stepCompleted, entries)).isInstanceOf(UncheckedSqlException.class);

            assertThat(restarted().findById("s-1")).get().isEqualTo(before);
            assertThat(provider.findByAssociation("paymentId", "p-7")).isEmpty();
            assertThat(history("s-1")).extracting(row -> row.get("kind")).containsExactly("CREATED");
        }

        @Test
        @DisplayName("scenario 10: a failed first save leaves no saga_instance or saga_association row")
        void failedFirstSaveLeavesNothing() {
            PostgresTestSupport.failOn("saga_lifecycle_history", "INSERT");

            assertThatThrownBy(() -> provider.save(created("s-new"), List.of(createdEntry("s-new"))))
                    .isInstanceOf(UncheckedSqlException.class);

            assertThat(provider.findById("s-new")).isEmpty();
            assertThat(PostgresTestSupport.queryRows("SELECT * FROM saga_instance WHERE saga_id = ?", "s-new"))
                    .isEmpty();
            assertThat(PostgresTestSupport.queryRows("SELECT * FROM saga_association WHERE saga_id = ?", "s-new"))
                    .isEmpty();
            assertThat(history("s-new")).isEmpty();
        }
    }
}
