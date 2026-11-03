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

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class StepRecordsTest {

    private static final AggregateReference ORDER_1 = new AggregateReference("com.example.Order", "order-1");
    private static final AggregateReference ORDER_2 = new AggregateReference("com.example.Order", "order-2");
    private static final AggregateReference PAYMENT_1 = new AggregateReference("com.example.Payment", "pay-1");
    private static final Instant NOW = Instant.now();

    static final class SampleAggregate {
    }

    @Nested
    @DisplayName("AggregateReference")
    class AggregateReferenceTests {

        @Test
        @DisplayName("null aggregateType is rejected with IllegalArgumentException")
        void nullTypeRejected() {
            assertThatThrownBy(() -> new AggregateReference(null, "id"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null aggregateId is rejected with IllegalArgumentException")
        void nullIdRejected() {
            assertThatThrownBy(() -> new AggregateReference("com.example.Order", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("of(Class, Object) uses the fully qualified class name and String.valueOf of the id")
        void ofFactory() {
            var reference = AggregateReference.of(SampleAggregate.class, 42L);

            assertThat(reference.aggregateType()).isEqualTo(SampleAggregate.class.getName());
            assertThat(reference.aggregateId()).isEqualTo("42");
        }

        @Test
        @DisplayName("of(Class, Object) rejects null arguments with IllegalArgumentException")
        void ofRejectsNulls() {
            assertThatThrownBy(() -> AggregateReference.of(null, "id"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AggregateReference.of(SampleAggregate.class, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("StepRecord")
    class StepRecordTests {

        @Test
        @DisplayName("null status is rejected with IllegalArgumentException")
        void nullStatusRejected() {
            assertThatThrownBy(() -> new StepRecord(null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null touchedAggregates is rejected with IllegalArgumentException")
        void nullListRejected() {
            assertThatThrownBy(() -> new StepRecord(StepStatus.COMPLETED, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null element in touchedAggregates is rejected with IllegalArgumentException")
        void nullElementRejected() {
            var withNull = Arrays.asList(ORDER_1, null);

            assertThatThrownBy(() -> StepRecord.completed(withNull))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("NOT_RUN with touched aggregates is rejected with IllegalArgumentException")
        void notRunWithAggregatesRejected() {
            assertThatThrownBy(() -> new StepRecord(StepStatus.NOT_RUN, List.of(ORDER_1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("modifying the original list after construction does not affect the record")
        void defensiveCopy() {
            var original = new ArrayList<>(List.of(ORDER_1));
            var record = StepRecord.completed(original);

            original.add(ORDER_2);

            assertThat(record.touchedAggregates()).containsExactly(ORDER_1);
        }

        @Test
        @DisplayName("touchedAggregates() returns an unmodifiable list")
        void unmodifiable() {
            var record = StepRecord.completed(List.of(ORDER_1));

            assertThatThrownBy(() -> record.touchedAggregates().add(ORDER_2))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("duplicates are dropped, keeping first-recorded order")
        void dedupPreservesOrder() {
            var record = StepRecord.completed(List.of(PAYMENT_1, ORDER_1, PAYMENT_1, ORDER_2, ORDER_1));

            assertThat(record.touchedAggregates()).containsExactly(PAYMENT_1, ORDER_1, ORDER_2);
        }

        @Test
        @DisplayName("notRun() is NOT_RUN with no aggregates")
        void notRunFactory() {
            var record = StepRecord.notRun();

            assertThat(record.status()).isEqualTo(StepStatus.NOT_RUN);
            assertThat(record.touchedAggregates()).isEmpty();
        }

        @Test
        @DisplayName("completed(list) is COMPLETED with the given aggregates; an empty list is allowed")
        void completedFactory() {
            assertThat(StepRecord.completed(List.of(ORDER_1)))
                    .isEqualTo(new StepRecord(StepStatus.COMPLETED, List.of(ORDER_1)));
            assertThat(StepRecord.completed(List.of()).touchedAggregates()).isEmpty();
        }
    }

    /** touchedAggregates equals the input's distinct elements in first-occurrence order (FR-004). */
    @Property
    void dedupKeepsFirstOccurrenceOrder(@ForAll @Size(max = 30) List<@IntRange(min = 0, max = 5) Integer> ids) {
        var input = ids.stream().map(id -> new AggregateReference("com.example.Order", "order-" + id)).toList();

        var record = StepRecord.completed(input);

        assertThat(record.touchedAggregates()).isEqualTo(input.stream().distinct().toList());
    }

    @Nested
    @DisplayName("SagaFailure")
    class SagaFailureTests {

        @Test
        @DisplayName("null stepName, causeType, or failedAt is rejected with IllegalArgumentException")
        void nullRequiredComponentsRejected() {
            assertThatThrownBy(() -> new SagaFailure(null, "java.lang.RuntimeException", "m", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SagaFailure("processPayment", null, "m", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SagaFailure("processPayment", "java.lang.RuntimeException", "m", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null message is allowed")
        void nullMessageAllowed() {
            var failure = new SagaFailure("processPayment", "java.lang.RuntimeException", null, NOW);

            assertThat(failure.message()).isNull();
        }

        @Test
        @DisplayName("of(step, cause, at) records the cause's class name and message")
        void ofFactory() {
            var failure = SagaFailure.of("processPayment", new IllegalStateException("declined"), NOW);

            assertThat(failure).isEqualTo(
                    new SagaFailure("processPayment", IllegalStateException.class.getName(), "declined", NOW));
        }

        @Test
        @DisplayName("of(step, cause, at) rejects null arguments with IllegalArgumentException")
        void ofRejectsNulls() {
            var cause = new RuntimeException();
            assertThatThrownBy(() -> SagaFailure.of(null, cause, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SagaFailure.of("processPayment", null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SagaFailure.of("processPayment", cause, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
