package io.github.gkosharovdev.sagaforge.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaContextRecordTest {

    private static final String SAGA_ID = "saga-123";
    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("exposes sagaId, values, and lastModifiedAt as given")
    void exposesComponents() {
        var record = new SagaContextRecord(SAGA_ID, Map.of("total", 42, "address", Map.of("city", "Sofia")), NOW);

        assertThat(record.sagaId()).isEqualTo(SAGA_ID);
        assertThat(record.values()).containsEntry("total", 42).containsEntry("address", Map.of("city", "Sofia"));
        assertThat(record.lastModifiedAt()).isEqualTo(NOW);
    }

    @Nested
    @DisplayName("null rules")
    class NullRules {

        @Test
        @DisplayName("null sagaId is rejected")
        void nullSagaIdRejected() {
            assertThatThrownBy(() -> new SagaContextRecord(null, Map.of(), NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sagaId");
        }

        @Test
        @DisplayName("null values map is rejected")
        void nullValuesRejected() {
            assertThatThrownBy(() -> new SagaContextRecord(SAGA_ID, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("values");
        }

        @Test
        @DisplayName("null lastModifiedAt is rejected")
        void nullLastModifiedAtRejected() {
            assertThatThrownBy(() -> new SagaContextRecord(SAGA_ID, Map.of(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastModifiedAt");
        }

        @Test
        @DisplayName("a null value inside values is rejected")
        void nullValueEntryRejected() {
            var values = new HashMap<String, Object>();
            values.put("total", null);

            assertThatThrownBy(() -> new SagaContextRecord(SAGA_ID, values, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("total");
        }

        @Test
        @DisplayName("a null key inside values is rejected")
        void nullKeyEntryRejected() {
            var values = new HashMap<String, Object>();
            values.put(null, "x");

            assertThatThrownBy(() -> new SagaContextRecord(SAGA_ID, values, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an empty values map is accepted")
        void emptyValuesAccepted() {
            assertThat(new SagaContextRecord(SAGA_ID, Map.of(), NOW).values()).isEmpty();
        }
    }

    @Nested
    @DisplayName("immutability and defensive copy")
    class Immutability {

        @Test
        @DisplayName("values() is unmodifiable")
        void valuesUnmodifiable() {
            var record = new SagaContextRecord(SAGA_ID, Map.of("total", 42), NOW);

            assertThatThrownBy(() -> record.values().put("other", 1))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> record.values().remove("total"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> record.values().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("mutating the source map after construction does not affect the record")
        void defensiveCopy() {
            var source = new HashMap<String, Object>();
            source.put("total", 42);

            var record = new SagaContextRecord(SAGA_ID, source, NOW);
            source.put("sneaky", "mutation");
            source.remove("total");

            assertThat(record.values()).containsOnlyKeys("total");
        }
    }
}
