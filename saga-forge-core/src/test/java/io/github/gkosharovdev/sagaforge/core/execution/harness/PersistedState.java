package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;

import java.util.List;

/**
 * What a saga test harness has persisted: its stored saga instances, its {@code SagaContext} records and the
 * contents of its {@link InMemoryAggregateStore}. Captured with {@link SagaTestHarness#persistedState()} and
 * turned into a fresh harness with {@link SagaTestHarness#fromPersistedState(PersistedState)}, modelling a
 * later unit of work that runs in a different application instance from persisted state alone (spec FR-020,
 * SC-003; contracts/saga-unit-of-work.md "Invariants").
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <h2>No shared memory</h2>
 * <p>{@link SagaInstance} and harness-written {@link SagaContextRecord}s are immutable, so the two lists are
 * plain unmodifiable copies. The aggregate store is copied with its registered copiers
 * ({@link InMemoryAggregateStore#copy()}) when the state is captured, again every time {@link #aggregates()} is
 * read, and again by each of {@link #newAggregateStore()}: neither the original harness, this state, nor any
 * harness built from it share a mutable aggregate, provided a copier is registered for every mutable aggregate
 * type in the store (types without one are shared by reference). The same {@code PersistedState} can seed any
 * number of fresh harnesses.</p>
 *
 * <h2>Client-held state is not included</h2>
 * <p>State held by client objects registered with {@link SagaTestHarness.Builder#handler(Object)},
 * {@link SagaTestHarness.Builder#repository(Object)} or {@link SagaTestHarness.Builder#participant
 * participant(...)} (for example a repository backed by its own map) lives outside the harness stores, just as
 * a real database lives outside the saga engine. To carry it forward, register the same objects (the
 * "database" survives the restart) or copies of them on the builder returned by
 * {@code fromPersistedState(...)}. Nothing else carries over: saga registrations, handlers, aggregates,
 * resolver, clock, saga id generator, event log and dispatch log all start fresh.</p>
 *
 * @param instances      every stored saga instance, in storage order; unmodifiable
 * @param contextRecords every stored {@code SagaContext} record, in storage order; unmodifiable
 * @param aggregates     a private copy of the aggregate store; the accessor returns a fresh copy
 */
public record PersistedState(
        List<SagaInstance> instances,
        List<SagaContextRecord> contextRecords,
        InMemoryAggregateStore aggregates) {

    /**
     * @throws IllegalArgumentException if any argument is {@code null} or a list contains {@code null}
     */
    public PersistedState {
        if (instances == null || contextRecords == null || aggregates == null) {
            throw new IllegalArgumentException("instances, contextRecords and aggregates must not be null");
        }
        try {
            instances = List.copyOf(instances);
            contextRecords = List.copyOf(contextRecords);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("instances and contextRecords must not contain null", e);
        }
        aggregates = aggregates.copy();
    }

    /** Captures the given stores (copies; the stores are not retained). */
    static PersistedState of(
            InMemoryStorageProvider storage, InMemorySagaContextStore contextStore, InMemoryAggregateStore aggregates) {
        return new PersistedState(storage.all(), contextStore.all(), aggregates);
    }

    /** A fresh copy of the captured aggregate store; changes to it do not affect this state. */
    @Override
    public InMemoryAggregateStore aggregates() {
        return aggregates.copy();
    }

    /** A new storage provider holding {@link #instances()}, in order. */
    public InMemoryStorageProvider newStorage() {
        InMemoryStorageProvider storage = new InMemoryStorageProvider();
        instances.forEach(storage::save);
        return storage;
    }

    /** A new context store holding {@link #contextRecords()}, in order. */
    public InMemorySagaContextStore newContextStore() {
        InMemorySagaContextStore store = new InMemorySagaContextStore();
        contextRecords.forEach(store::save);
        return store;
    }

    /** A new aggregate store: a copy of the captured one, with its copiers. */
    public InMemoryAggregateStore newAggregateStore() {
        return aggregates.copy();
    }
}
