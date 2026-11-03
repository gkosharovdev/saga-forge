package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SagaInstanceTest {

    private static final String SAGA_ID = "saga-123";
    private static final String SAGA_TYPE = "com.example.OrderSaga";
    private static final SagaStatus STATUS = SagaStatus.RUNNING;
    private static final Instant NOW = Instant.now();

    private static final SagaFailure FAILURE = new SagaFailure("processPayment", "java.lang.IllegalStateException",
            "declined", NOW);

    private SagaInstance createInstance(Map<String, Object> state, Map<String, String> associations) {
        return new SagaInstance(SAGA_ID, SAGA_TYPE, state, associations, STATUS, NOW, NOW, Map.of(), null, null);
    }

    private SagaInstance createInstance(SagaStatus status, Map<String, StepRecord> steps, String outcome,
                                        SagaFailure failure) {
        return new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of(), status, NOW, NOW, steps, outcome, failure);
    }

    @Nested
    @DisplayName("state map immutability")
    class StateMapImmutability {

        @Test
        @DisplayName("state() returns an unmodifiable map — put throws UnsupportedOperationException")
        void statePutThrows() {
            var instance = createInstance(Map.of("key", "value"), Map.of());
            assertThatThrownBy(() -> instance.state().put("new", "entry"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("state() returns an unmodifiable map — remove throws UnsupportedOperationException")
        void stateRemoveThrows() {
            var instance = createInstance(Map.of("key", "value"), Map.of());
            assertThatThrownBy(() -> instance.state().remove("key"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("state() returns an unmodifiable map — clear throws UnsupportedOperationException")
        void stateClearThrows() {
            var instance = createInstance(Map.of("key", "value"), Map.of());
            assertThatThrownBy(() -> instance.state().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("associations map immutability")
    class AssociationsMapImmutability {

        @Test
        @DisplayName("associations() returns an unmodifiable map — put throws UnsupportedOperationException")
        void associationsPutThrows() {
            var instance = createInstance(Map.of(), Map.of("orderId", "order-1"));
            assertThatThrownBy(() -> instance.associations().put("new", "entry"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("associations() returns an unmodifiable map — remove throws UnsupportedOperationException")
        void associationsRemoveThrows() {
            var instance = createInstance(Map.of(), Map.of("orderId", "order-1"));
            assertThatThrownBy(() -> instance.associations().remove("orderId"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("associations() returns an unmodifiable map — clear throws UnsupportedOperationException")
        void associationsClearThrows() {
            var instance = createInstance(Map.of(), Map.of("orderId", "order-1"));
            assertThatThrownBy(() -> instance.associations().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("defensive copy behavior")
    class DefensiveCopy {

        @Test
        @DisplayName("modifying original state map after construction does not affect the record")
        void stateDefensiveCopy() {
            var originalState = new HashMap<String, Object>();
            originalState.put("key", "value");

            var instance = createInstance(originalState, Map.of());

            originalState.put("sneaky", "mutation");

            assertThat(instance.state()).containsOnlyKeys("key");
            assertThat(instance.state()).doesNotContainKey("sneaky");
        }

        @Test
        @DisplayName("modifying original associations map after construction does not affect the record")
        void associationsDefensiveCopy() {
            var originalAssociations = new HashMap<String, String>();
            originalAssociations.put("orderId", "order-1");

            var instance = createInstance(Map.of(), originalAssociations);

            originalAssociations.put("sneaky", "mutation");

            assertThat(instance.associations()).containsOnlyKeys("orderId");
            assertThat(instance.associations()).doesNotContainKey("sneaky");
        }
    }

    @Nested
    @DisplayName("null rejection via Map.copyOf")
    class NullRejection {

        @Test
        @DisplayName("null value in state map is rejected with NullPointerException")
        void stateNullValueRejected() {
            var stateWithNull = new HashMap<String, Object>();
            stateWithNull.put("key", null);

            assertThatThrownBy(() -> createInstance(stateWithNull, Map.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null value in associations map is rejected with NullPointerException")
        void associationsNullValueRejected() {
            var associationsWithNull = new HashMap<String, String>();
            associationsWithNull.put("key", null);

            assertThatThrownBy(() -> createInstance(Map.of(), associationsWithNull))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("steps map")
    class Steps {

        @Test
        @DisplayName("null steps map is rejected with IllegalArgumentException")
        void nullStepsRejected() {
            assertThatThrownBy(() -> createInstance(STATUS, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("steps");
        }

        @Test
        @DisplayName("null key in steps map is rejected with IllegalArgumentException")
        void nullStepKeyRejected() {
            var steps = new HashMap<String, StepRecord>();
            steps.put(null, StepRecord.notRun());

            assertThatThrownBy(() -> createInstance(STATUS, steps, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null keys");
        }

        @Test
        @DisplayName("null value in steps map is rejected with IllegalArgumentException")
        void nullStepValueRejected() {
            var steps = new HashMap<String, StepRecord>();
            steps.put("processPayment", null);

            assertThatThrownBy(() -> createInstance(STATUS, steps, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("processPayment");
        }

        @Test
        @DisplayName("modifying original steps map after construction does not affect the record")
        void stepsDefensiveCopy() {
            var steps = new HashMap<String, StepRecord>();
            steps.put("handleOrderCreated", StepRecord.completed(List.of(new AggregateReference("Order", "o-1"))));

            var instance = createInstance(STATUS, steps, null, null);
            steps.put("processPayment", StepRecord.notRun());

            assertThat(instance.steps()).containsOnlyKeys("handleOrderCreated");
            assertThat(instance.steps().get("handleOrderCreated").touchedAggregates())
                    .containsExactly(new AggregateReference("Order", "o-1"));
        }

        @Test
        @DisplayName("steps() returns an unmodifiable map — put throws UnsupportedOperationException")
        void stepsPutThrows() {
            var instance = createInstance(STATUS, Map.of("processPayment", StepRecord.notRun()), null, null);

            assertThatThrownBy(() -> instance.steps().put("other", StepRecord.notRun()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("outcome is present if and only if status is COMPLETED")
    class Outcome {

        @Test
        @DisplayName("COMPLETED with an outcome is accepted")
        void completedWithOutcomeAccepted() {
            var instance = createInstance(SagaStatus.COMPLETED, Map.of(), "completeOrder", null);

            assertThat(instance.outcome()).isEqualTo("completeOrder");
            assertThat(instance.failure()).isNull();
        }

        @Test
        @DisplayName("COMPLETED without an outcome is rejected")
        void completedWithoutOutcomeRejected() {
            assertThatThrownBy(() -> createInstance(SagaStatus.COMPLETED, Map.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome");
        }

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("any non-COMPLETED status with an outcome is rejected")
        void nonCompletedWithOutcomeRejected(SagaStatus status) {
            SagaFailure failure = status == SagaStatus.FAILED ? FAILURE : null;

            assertThatThrownBy(() -> createInstance(status, Map.of(), "completeOrder", failure))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outcome");
        }
    }

    @Nested
    @DisplayName("failure is present if and only if status is FAILED")
    class Failure {

        @Test
        @DisplayName("FAILED with a failure is accepted")
        void failedWithFailureAccepted() {
            var instance = createInstance(SagaStatus.FAILED, Map.of(), null, FAILURE);

            assertThat(instance.failure()).isEqualTo(FAILURE);
            assertThat(instance.outcome()).isNull();
        }

        @Test
        @DisplayName("FAILED without a failure is rejected")
        void failedWithoutFailureRejected() {
            assertThatThrownBy(() -> createInstance(SagaStatus.FAILED, Map.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("failure");
        }

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("any non-FAILED status with a failure is rejected")
        void nonFailedWithFailureRejected(SagaStatus status) {
            String outcome = status == SagaStatus.COMPLETED ? "completeOrder" : null;

            assertThatThrownBy(() -> createInstance(status, Map.of(), outcome, FAILURE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("failure");
        }
    }
}
