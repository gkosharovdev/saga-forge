package io.github.gkosharovdev.sagaforge.engine.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.lifecycle.NonStructuredStateValueException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaStateTypeMismatchException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link EngineSagaContext}: the value semantics of features 001/002 and write-through to the
 * {@code SagaContextStore} after every change (specs/004-saga-engine tasks.md T043, FR-013).
 */
class EngineSagaContextTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SAGA = "saga-1";

    /** Counts saves and keeps every record handed to it, delegating to an in-memory store. */
    private static final class RecordingContextStore implements SagaContextStore {

        private final InMemorySagaContextStore delegate = new InMemorySagaContextStore();
        private final List<SagaContextRecord> saved = new ArrayList<>();

        @Override
        public Optional<SagaContextRecord> find(String sagaId) {
            return delegate.find(sagaId);
        }

        @Override
        public void save(SagaContextRecord record) {
            saved.add(record);
            delegate.save(record);
        }

        @Override
        public void delete(String sagaId) {
            delegate.delete(sagaId);
        }
    }

    private final RecordingContextStore store = new RecordingContextStore();

    private EngineSagaContext context(String sagaId) {
        return new EngineSagaContext(sagaId, store, CLOCK);
    }

    @Nested
    @DisplayName("names and values")
    class NamesAndValues {

        private final EngineSagaContext context = context(SAGA);

        @Test
        @DisplayName("null or blank names and fields are rejected, and nothing is written")
        void blankNamesRejected() {
            assertThatThrownBy(() -> context.putState(null, 1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.putState(" ", 1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.getState("", Long.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.updateStateAttribute(" ", "f", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.updateStateAttribute("s", " ", 1))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(store.saved).isEmpty();
        }

        @Test
        @DisplayName("null values and a null type are rejected, and nothing is written")
        void nullValuesRejected() {
            assertThatThrownBy(() -> context.putState("amount", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.updateStateAttribute("s", "f", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> context.getState("amount", null)).isInstanceOf(IllegalArgumentException.class);

            assertThat(store.saved).isEmpty();
        }

        @Test
        @DisplayName("a missing value reads as empty")
        void missingValueIsEmpty() {
            assertThat(context.getState("amount", Long.class)).isEmpty();
        }

        @Test
        @DisplayName("reads are type-safe: a compatible type returns the value, another type fails")
        void typeSafeReads() {
            context.putState("amount", 250L);

            assertThat(context.getState("amount", Long.class)).contains(250L);
            assertThat(context.getState("amount", Number.class)).contains(250L);
            assertThatThrownBy(() -> context.getState("amount", String.class))
                    .isInstanceOf(SagaStateTypeMismatchException.class);
        }

        @Test
        @DisplayName("putState replaces an earlier value")
        void putReplaces() {
            context.putState("amount", 250L);
            context.putState("amount", "two hundred");

            assertThat(context.getState("amount", String.class)).contains("two hundred");
        }
    }

    @Nested
    @DisplayName("structured values")
    class Structured {

        private final EngineSagaContext context = context(SAGA);

        @Test
        @DisplayName("updateStateAttribute creates the structured value and sets fields on it")
        void updateCreatesAndSets() {
            context.updateStateAttribute("address", "city", "Sofia");
            context.updateStateAttribute("address", "zip", 1000);

            assertThat(context.getState("address", Map.class))
                    .hasValueSatisfying(map -> assertThat(map).containsExactly(
                            Map.entry("city", "Sofia"), Map.entry("zip", 1000)));
        }

        @Test
        @DisplayName("a structured value set with putState can be updated, and the original map is not changed")
        void putThenUpdate() {
            Map<String, Object> original = new java.util.LinkedHashMap<>(Map.of("city", "Sofia"));
            context.putState("address", original);
            context.updateStateAttribute("address", "zip", 1000);

            assertThat(original).containsOnlyKeys("city");
            assertThat(context.getState("address", Map.class))
                    .hasValueSatisfying(map -> assertThat(map).containsOnlyKeys("city", "zip"));
        }

        @Test
        @DisplayName("a structured value read back is unmodifiable")
        void readBackIsUnmodifiable() {
            context.updateStateAttribute("address", "city", "Sofia");

            @SuppressWarnings("unchecked")
            Map<Object, Object> read = context.getState("address", Map.class).orElseThrow();
            assertThatThrownBy(() -> read.put("zip", 1000)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("updating a scalar value fails with NonStructuredStateValueException and writes nothing")
        void updatingScalarFails() {
            context.putState("amount", 250L);
            int savesBefore = store.saved.size();

            assertThatThrownBy(() -> context.updateStateAttribute("amount", "currency", "EUR"))
                    .isInstanceOf(NonStructuredStateValueException.class);
            assertThat(store.saved).hasSize(savesBefore);
            assertThat(context.getState("amount", Long.class)).contains(250L);
        }
    }

    @Nested
    @DisplayName("write-through")
    class WriteThrough {

        @Test
        @DisplayName("every putState and updateStateAttribute saves a full record of the instance's values")
        void saveAfterEveryCall() {
            EngineSagaContext context = context(SAGA);

            context.putState("amount", 250L);
            context.updateStateAttribute("address", "city", "Sofia");
            context.putState("claimId", "claim-1");

            assertThat(store.saved).hasSize(3);
            assertThat(store.saved).allSatisfy(record -> {
                assertThat(record.sagaId()).isEqualTo(SAGA);
                assertThat(record.lastModifiedAt()).isEqualTo(NOW);
            });
            assertThat(store.saved.get(0).values()).isEqualTo(Map.of("amount", 250L));
            assertThat(store.saved.get(1).values())
                    .isEqualTo(Map.of("amount", 250L, "address", Map.of("city", "Sofia")));
            assertThat(store.saved.get(2).values()).isEqualTo(
                    Map.of("amount", 250L, "address", Map.of("city", "Sofia"), "claimId", "claim-1"));
        }

        @Test
        @DisplayName("a read saves nothing")
        void readsSaveNothing() {
            EngineSagaContext context = context(SAGA);
            context.getState("amount", Long.class);

            assertThat(store.saved).isEmpty();
        }

        @Test
        @DisplayName("a new context starts from the stored record")
        void startsFromStoredRecord() {
            store.delegate.save(new SagaContextRecord(SAGA, Map.of("amount", 250L,
                    "address", Map.of("city", "Sofia")), NOW));

            EngineSagaContext context = context(SAGA);
            context.updateStateAttribute("address", "zip", 1000);

            assertThat(context.getState("amount", Long.class)).contains(250L);
            assertThat(store.find(SAGA)).hasValueSatisfying(record -> assertThat(record.values()).isEqualTo(
                    Map.of("amount", 250L, "address", Map.of("city", "Sofia", "zip", 1000))));
        }
    }

    @Test
    @DisplayName("two sagaIds over one store never see each other's values")
    void isolationBetweenInstances() {
        EngineSagaContext first = context("saga-a");
        EngineSagaContext second = context("saga-b");

        first.putState("amount", 1L);
        second.putState("amount", 2L);
        second.putState("only-b", "b");

        assertThat(first.getState("amount", Long.class)).contains(1L);
        assertThat(first.getState("only-b", String.class)).isEmpty();
        assertThat(second.getState("amount", Long.class)).contains(2L);
        assertThat(store.find("saga-a")).hasValueSatisfying(
                record -> assertThat(record.values()).isEqualTo(Map.of("amount", 1L)));
        assertThat(store.find("saga-b")).hasValueSatisfying(
                record -> assertThat(record.values()).isEqualTo(Map.of("amount", 2L, "only-b", "b")));
    }

    @Test
    @DisplayName("null constructor arguments are rejected")
    void nullConstructorArguments() {
        assertThatThrownBy(() -> new EngineSagaContext(null, store, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineSagaContext(SAGA, null, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineSagaContext(SAGA, store, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
