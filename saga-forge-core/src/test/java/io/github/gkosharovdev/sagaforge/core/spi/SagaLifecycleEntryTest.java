package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link SagaLifecycleEntry} validation per specs/003-postgres-saga-persistence/contracts/saga-lifecycle-entry.md.
 */
class SagaLifecycleEntryTest {

    private static final String SAGA_ID = "s-1";
    private static final Instant AT = Instant.parse("2026-01-01T10:15:30Z");
    private static final AggregateReference ORDER_1 = new AggregateReference("com.example.Order", "order-1");
    private static final AggregateReference ORDER_2 = new AggregateReference("com.example.Order", "order-2");
    private static final SagaFailure FAILURE =
            new SagaFailure("processPayment", "java.lang.IllegalStateException", "declined", AT);

    private static SagaLifecycleEntry entry(SagaLifecycleEventKind kind, String stepName,
                                            List<AggregateReference> touched, String outcomeStep,
                                            SagaFailure failure) {
        return new SagaLifecycleEntry(SAGA_ID, kind, stepName, touched, outcomeStep, failure, AT);
    }

    @Nested
    @DisplayName("valid entries")
    class Valid {

        @Test
        @DisplayName("CREATED has no step, aggregates, outcome or failure")
        void created() {
            var created = entry(SagaLifecycleEventKind.CREATED, null, List.of(), null, null);

            assertThat(created.kind()).isEqualTo(SagaLifecycleEventKind.CREATED);
            assertThat(created.stepName()).isNull();
            assertThat(created.touchedAggregates()).isEmpty();
            assertThat(created.occurredAt()).isEqualTo(AT);
        }

        @Test
        @DisplayName("STEP_COMPLETED carries its step name and touched aggregates (empty allowed)")
        void stepCompleted() {
            var completed = new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.STEP_COMPLETED,
                    "processPayment", List.of(ORDER_1), null, null, AT);

            assertThat(completed.stepName()).isEqualTo("processPayment");
            assertThat(completed.touchedAggregates()).containsExactly(ORDER_1);
            assertThat(entry(SagaLifecycleEventKind.STEP_COMPLETED, "processPayment", List.of(), null, null)
                    .touchedAggregates()).isEmpty();
        }

        @Test
        @DisplayName("COMPLETED carries its outcome step")
        void completed() {
            var completed = entry(SagaLifecycleEventKind.COMPLETED, null, List.of(), "completeOrder", null);

            assertThat(completed.outcomeStep()).isEqualTo("completeOrder");
        }

        @Test
        @DisplayName("FAILED carries its failure")
        void failed() {
            var failed = entry(SagaLifecycleEventKind.FAILED, null, List.of(), null, FAILURE);

            assertThat(failed.failure()).isEqualTo(FAILURE);
        }
    }

    @Nested
    @DisplayName("required fields")
    class Required {

        @Test
        @DisplayName("null sagaId, kind, occurredAt or touchedAggregates is rejected")
        void nullsRejected() {
            assertThatThrownBy(() -> new SagaLifecycleEntry(null, SagaLifecycleEventKind.CREATED, null,
                    List.of(), null, null, AT)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SagaLifecycleEntry(SAGA_ID, null, null, List.of(), null, null, AT))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SagaLifecycleEntry(SAGA_ID, SagaLifecycleEventKind.CREATED, null,
                    List.of(), null, null, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.CREATED, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null element in touchedAggregates is rejected")
        void nullElementRejected() {
            var withNull = Arrays.asList(ORDER_1, null);

            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.STEP_COMPLETED, "processPayment", withNull,
                    null, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("kind-dependent fields (present iff)")
    class PresentIff {

        @Test
        @DisplayName("stepName is required for STEP_COMPLETED and rejected for every other kind")
        void stepName() {
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.STEP_COMPLETED, null, List.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.CREATED, "processPayment", List.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.COMPLETED, "processPayment", List.of(),
                    "completeOrder", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.FAILED, "processPayment", List.of(), null,
                    FAILURE)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("outcomeStep is required for COMPLETED and rejected for every other kind")
        void outcomeStep() {
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.COMPLETED, null, List.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.CREATED, null, List.of(), "completeOrder", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.STEP_COMPLETED, "processPayment", List.of(),
                    "completeOrder", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.FAILED, null, List.of(), "completeOrder",
                    FAILURE)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("failure is required for FAILED and rejected for every other kind")
        void failure() {
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.FAILED, null, List.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.CREATED, null, List.of(), null, FAILURE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.STEP_COMPLETED, "processPayment", List.of(),
                    null, FAILURE)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.COMPLETED, null, List.of(), "completeOrder",
                    FAILURE)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("non-empty touchedAggregates is rejected for every kind but STEP_COMPLETED")
        void touchedAggregatesOnlyForStepCompleted() {
            var touched = List.of(ORDER_1);

            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.CREATED, null, touched, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.COMPLETED, null, touched, "completeOrder", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> entry(SagaLifecycleEventKind.FAILED, null, touched, null, FAILURE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("touchedAggregates copy")
    class DefensiveCopy {

        @Test
        @DisplayName("modifying the original list after construction does not affect the entry")
        void defensiveCopy() {
            var original = new ArrayList<>(List.of(ORDER_1));
            var completed = entry(SagaLifecycleEventKind.STEP_COMPLETED, "processPayment", original, null, null);

            original.add(ORDER_2);

            assertThat(completed.touchedAggregates()).containsExactly(ORDER_1);
        }

        @Test
        @DisplayName("touchedAggregates() returns an unmodifiable list")
        void unmodifiable() {
            var completed = entry(SagaLifecycleEventKind.STEP_COMPLETED, "processPayment", List.of(ORDER_1), null,
                    null);

            assertThatThrownBy(() -> completed.touchedAggregates().add(ORDER_2))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
