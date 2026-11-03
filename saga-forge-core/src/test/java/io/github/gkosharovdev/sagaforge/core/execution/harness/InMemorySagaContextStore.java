package io.github.gkosharovdev.sagaforge.core.execution.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;

/**
 * Map-backed {@link SagaContextStore} for the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main}; the production implementations ship with the storage modules.</p>
 *
 * <p><strong>Never snapshotted.</strong> Unlike {@link InMemoryStorageProvider} and
 * {@link InMemoryAggregateStore}, this store has no snapshot or restore: it models the
 * {@link SagaContextStore} contract that a context write does not join the saga-scoped unit of work, so
 * values written by a step survive that unit of work's rollback. The harness must not reset it on
 * rollback.</p>
 *
 * <p>Records are kept in insertion order. Not thread-safe.</p>
 */
public final class InMemorySagaContextStore implements SagaContextStore {

    private final Map<String, SagaContextRecord> records = new LinkedHashMap<>();

    @Override
    public Optional<SagaContextRecord> find(String sagaId) {
        requireSagaId(sagaId);
        return Optional.ofNullable(records.get(sagaId));
    }

    @Override
    public void save(SagaContextRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        records.put(record.sagaId(), record);
    }

    @Override
    public void delete(String sagaId) {
        requireSagaId(sagaId);
        records.remove(sagaId);
    }

    /**
     * Returns every stored record, in insertion order.
     *
     * @return an unmodifiable list of records; never {@code null}
     */
    public List<SagaContextRecord> all() {
        return List.copyOf(records.values());
    }

    /**
     * Returns a new, independent store holding the same records in the same order. A
     * {@link SagaContextRecord} written by the harness holds unmodifiable values, so sharing the records shares
     * no mutable memory: later writes to either store are invisible to the other.
     *
     * @return a new store; never {@code null}
     */
    public InMemorySagaContextStore copy() {
        InMemorySagaContextStore copy = new InMemorySagaContextStore();
        copy.records.putAll(records);
        return copy;
    }

    private static void requireSagaId(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
    }
}
