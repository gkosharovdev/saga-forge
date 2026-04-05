package io.github.gkosharovdev.sagaforge.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaInstanceTest {

    private static final String SAGA_ID = "saga-123";
    private static final String SAGA_TYPE = "com.example.OrderSaga";
    private static final SagaStatus STATUS = SagaStatus.RUNNING;
    private static final Instant NOW = Instant.now();

    private SagaInstance createInstance(Map<String, Object> state, Map<String, String> associations) {
        return new SagaInstance(SAGA_ID, SAGA_TYPE, state, associations, STATUS, NOW, NOW);
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
}
