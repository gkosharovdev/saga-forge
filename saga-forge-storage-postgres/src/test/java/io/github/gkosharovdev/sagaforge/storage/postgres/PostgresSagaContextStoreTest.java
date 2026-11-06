package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PostgresSagaContextStore} against a real PostgreSQL (spec US2, quickstart scenarios 4 to 6, 14 and 15;
 * FR-005, FR-006, SC-002). Instances are saved through {@link PostgresStorageProvider} first, because
 * {@code saga_context.saga_id} references {@code saga_instance} (research.md §8). Every {@link Instant} is
 * truncated to microseconds, the {@code timestamptz} precision ({@code .specify/memory/memory.md} G-003-05).
 */
class PostgresSagaContextStoreTest {

    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:15:30.123456789Z")
            .truncatedTo(ChronoUnit.MICROS);
    private static final Instant MODIFIED_AT = CREATED_AT.plusSeconds(42).plusNanos(987_000);

    private PostgresStorageProvider provider;
    private PostgresSagaContextStore store;

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
        provider = new PostgresStorageProvider(PostgresTestSupport.newDataSource());
        store = new PostgresSagaContextStore(PostgresTestSupport.newDataSource());
    }

    private static SagaInstance running(String sagaId, Map<String, Object> state) {
        return new SagaInstance(sagaId, SAGA_TYPE, state, Map.of("orderId", "o-" + sagaId), SagaStatus.RUNNING,
                CREATED_AT, MODIFIED_AT,
                Map.of("handleOrderCreated", StepRecord.completed(List.of()), "processPayment", StepRecord.notRun()),
                null, null);
    }

    private static List<Map<String, Object>> contextRows(String sagaId) {
        return PostgresTestSupport.queryRows("SELECT saga_id FROM saga_context WHERE saga_id = ?", sagaId);
    }

    @Test
    @DisplayName("scenario 4: the context record survives a rolled-back instance save (SC-002)")
    void contextSurvivesInstanceRollback() {
        SagaInstance before = running("s-1", Map.of("attempt", 1));
        provider.save(before);
        SagaContextRecord record = new SagaContextRecord("s-1", Map.of("paymentId", "p-7"), MODIFIED_AT);
        store.save(record);

        PostgresTestSupport.failOn("saga_instance", "UPDATE");
        assertThatThrownBy(() -> provider.save(running("s-1", Map.of("attempt", 2))))
                .isInstanceOf(UncheckedSqlException.class);

        assertThat(store.find("s-1")).contains(record);
        assertThat(provider.findById("s-1")).contains(before);
    }

    @Test
    @DisplayName("scenario 5: saving the same sagaId twice keeps only the latest values and lastModifiedAt")
    void resaveKeepsLatest() {
        provider.save(running("s-1", Map.of()));
        store.save(new SagaContextRecord("s-1", Map.of("paymentId", "p-1", "attempt", 1), CREATED_AT));
        SagaContextRecord latest = new SagaContextRecord("s-1", Map.of("paymentId", "p-2"), MODIFIED_AT);
        store.save(latest);

        assertThat(new PostgresSagaContextStore(PostgresTestSupport.newDataSource()).find("s-1")).contains(latest);
        assertThat(contextRows("s-1")).hasSize(1);
    }

    @Test
    @DisplayName("scenario 6: find for a sagaId with no record returns Optional.empty()")
    void missingRecordIsEmpty() {
        provider.save(running("s-1", Map.of()));

        assertThat(store.find("s-1")).isEmpty();
        assertThat(store.find("unknown")).isEmpty();
    }

    @Test
    @DisplayName("structured (nested map) and scalar values round-trip equal")
    void structuredAndScalarValuesRoundTrip() {
        provider.save(running("s-1", Map.of()));
        Map<String, Object> values = Map.of(
                "customerName", "Ada \"Lovelace\" Ünïcode",
                "express", true,
                "quantity", 3,
                "totalMillis", 9_000_000_000L,
                "discount", 0.15,
                "shipping", Map.of("city", "Sofia", "floor", 2,
                        "geo", Map.of("lat", 42.69, "lines", List.of("street 1", "block 7"))));
        SagaContextRecord record = new SagaContextRecord("s-1", values, MODIFIED_AT);
        store.save(record);

        assertThat(new PostgresSagaContextStore(PostgresTestSupport.newDataSource()).find("s-1")).contains(record);
    }

    @Test
    @DisplayName("delete(sagaId) removes the record and keeps the instance")
    void deleteRemovesRecordOnly() {
        SagaInstance instance = running("s-1", Map.of());
        provider.save(instance);
        store.save(new SagaContextRecord("s-1", Map.of("k", "v"), MODIFIED_AT));

        store.delete("s-1");
        store.delete("unknown");

        assertThat(store.find("s-1")).isEmpty();
        assertThat(contextRows("s-1")).isEmpty();
        assertThat(provider.findById("s-1")).contains(instance);
    }

    @Test
    @DisplayName("scenario 14 (context part): PostgresStorageProvider.delete also removes the record")
    void providerDeleteCascadesToContext() {
        provider.save(running("s-1", Map.of()));
        store.save(new SagaContextRecord("s-1", Map.of("k", "v"), MODIFIED_AT));

        provider.delete("s-1");

        assertThat(provider.findById("s-1")).isEmpty();
        assertThat(store.find("s-1")).isEmpty();
        assertThat(contextRows("s-1")).isEmpty();
    }

    @Test
    @DisplayName("null arguments and a null DataSource are rejected with IllegalArgumentException")
    void nullsAreRejected() {
        assertThatThrownBy(() -> store.find(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresSagaContextStore(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("scenario 15: saving a record with no saga_instance row throws and writes nothing")
    void saveWithoutInstanceFails() {
        SagaContextRecord orphan = new SagaContextRecord("no-instance", Map.of("k", "v"), MODIFIED_AT);

        assertThatThrownBy(() -> store.save(orphan)).isInstanceOf(UncheckedSqlException.class);

        assertThat(contextRows("no-instance")).isEmpty();
    }
}
