package io.github.gkosharovdev.sagaforge.core.execution.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;

/**
 * Map-backed {@link StorageProvider} for the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main}; the production storage implementations are separate modules.</p>
 *
 * <p>Part of the harness's simulated unit of work: the harness takes a {@link #snapshot()} when a unit of
 * work opens and calls {@link #restore(Object)} on rollback. Because {@link SagaInstance} is an immutable
 * record, copying the map is a full snapshot.</p>
 *
 * <p>Instances are kept in insertion order, so {@link #findByAssociation(String, String)} and {@link #all()}
 * return a deterministic order. Not thread-safe.</p>
 */
public final class InMemoryStorageProvider implements StorageProvider {

    private final Map<String, SagaInstance> instances = new LinkedHashMap<>();

    @Override
    public void save(SagaInstance instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        instances.put(instance.sagaId(), instance);
    }

    @Override
    public Optional<SagaInstance> findById(String sagaId) {
        requireSagaId(sagaId);
        return Optional.ofNullable(instances.get(sagaId));
    }

    @Override
    public List<SagaInstance> findByAssociation(String associationProperty, String associationValue) {
        if (associationProperty == null) {
            throw new IllegalArgumentException("associationProperty must not be null");
        }
        if (associationValue == null) {
            throw new IllegalArgumentException("associationValue must not be null");
        }
        List<SagaInstance> matches = new ArrayList<>();
        for (SagaInstance instance : instances.values()) {
            if (associationValue.equals(instance.associations().get(associationProperty))) {
                matches.add(instance);
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public void delete(String sagaId) {
        requireSagaId(sagaId);
        instances.remove(sagaId);
    }

    /**
     * Returns every stored instance, in insertion order.
     *
     * @return an unmodifiable list of stored instances; never {@code null}
     */
    public List<SagaInstance> all() {
        return List.copyOf(instances.values());
    }

    /**
     * Returns a new, independent provider holding the same instances in the same order. {@link SagaInstance}
     * is an immutable record, so sharing the instances shares no mutable memory: later writes to either
     * provider are invisible to the other.
     *
     * @return a new provider; never {@code null}
     */
    public InMemoryStorageProvider copy() {
        InMemoryStorageProvider copy = new InMemoryStorageProvider();
        copy.instances.putAll(instances);
        return copy;
    }

    /**
     * Captures the current contents of the store.
     *
     * @return an opaque snapshot, accepted only by {@link #restore(Object)} of this class
     */
    public Object snapshot() {
        return new Snapshot(new LinkedHashMap<>(instances));
    }

    /**
     * Replaces the contents of the store with a previously captured snapshot. A snapshot may be restored
     * more than once.
     *
     * @param snapshot a value returned by {@link #snapshot()}
     * @throws IllegalArgumentException if {@code snapshot} is {@code null} or was not produced by this class
     */
    public void restore(Object snapshot) {
        if (!(snapshot instanceof Snapshot s)) {
            throw new IllegalArgumentException("not an InMemoryStorageProvider snapshot: " + snapshot);
        }
        instances.clear();
        instances.putAll(s.instances());
    }

    private static void requireSagaId(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
    }

    private record Snapshot(Map<String, SagaInstance> instances) {
    }
}
