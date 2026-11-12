package io.github.gkosharovdev.sagaforge.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;

/**
 * The fixture's saga instances and lifecycle history, in memory. Writes join the unit of work's transaction: a
 * rollback restores the instances as they were at {@code begin()}. One unit of work runs at a time, so
 * {@link #findByIdAndLock} needs no lock.
 */
final class FixtureStorage implements StorageProvider, FixtureTransactions.Participant {

    private record Snapshot(Map<String, SagaInstance> instances, Map<String, List<SagaLifecycleEntry>> history) {
    }

    private Map<String, SagaInstance> instances = new LinkedHashMap<>();
    private Map<String, List<SagaLifecycleEntry>> history = new LinkedHashMap<>();

    @Override
    public synchronized void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries) {
        if (instance == null || newEntries == null) {
            throw new IllegalArgumentException("instance and newEntries must not be null");
        }
        instances.put(instance.sagaId(), instance);
        history.computeIfAbsent(instance.sagaId(), id -> new ArrayList<>()).addAll(newEntries);
    }

    @Override
    public synchronized Optional<SagaInstance> findById(String sagaId) {
        requireSagaId(sagaId);
        return Optional.ofNullable(instances.get(sagaId));
    }

    @Override
    public Optional<SagaInstance> findByIdAndLock(String sagaId) {
        return findById(sagaId);
    }

    @Override
    public synchronized List<SagaInstance> findByAssociation(String associationProperty, String associationValue) {
        if (associationProperty == null || associationValue == null) {
            throw new IllegalArgumentException("associationProperty and associationValue must not be null");
        }
        return instances.values().stream()
                .filter(instance -> associationValue.equals(instance.associations().get(associationProperty)))
                .toList();
    }

    @Override
    public synchronized void delete(String sagaId) {
        requireSagaId(sagaId);
        instances.remove(sagaId);
    }

    /** Every stored instance, in creation order. */
    synchronized List<SagaInstance> all() {
        return List.copyOf(instances.values());
    }

    /** The lifecycle history of {@code sagaId}, in write order. */
    synchronized List<SagaLifecycleEntry> history(String sagaId) {
        return List.copyOf(history.getOrDefault(sagaId, List.of()));
    }

    @Override
    public synchronized Object snapshot() {
        Map<String, List<SagaLifecycleEntry>> entries = new LinkedHashMap<>();
        history.forEach((id, list) -> entries.put(id, List.copyOf(list)));
        return new Snapshot(new LinkedHashMap<>(instances), entries);
    }

    @Override
    public synchronized void restore(Object snapshot) {
        Snapshot restored = (Snapshot) snapshot;
        instances = new LinkedHashMap<>(restored.instances());
        history = new LinkedHashMap<>();
        restored.history().forEach((id, list) -> history.put(id, new ArrayList<>(list)));
    }

    private static void requireSagaId(String sagaId) {
        if (sagaId == null) {
            throw new IllegalArgumentException("sagaId must not be null");
        }
    }
}
