package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@link AggregateResolver} of the saga test harness, backed by an {@link InMemoryAggregateStore}. It stands in for
 * the host integration's adapter over the client's repositories (spec FR-013).
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <ul>
 *   <li>{@link #supports(Class)}: {@code true} only for the types passed to the constructor (exact class), so an
 *       aggregate the test did not opt in still needs a client {@code @AggregateLoader}.</li>
 *   <li>{@link #load(Class, Object)}: the store entry under ({@code type}, {@code id}), or empty.</li>
 *   <li>{@link #save(Object)}: stores the aggregate under (its class, its {@code @AggregateIdentifier} value),
 *       replacing any earlier entry.</li>
 * </ul>
 * <p>Rollback is the store's: the harness snapshots the store with every unit of work. Register a copier on the
 * store ({@link InMemoryAggregateStore#registerCopier}) for aggregates whose in-place changes must roll back.</p>
 */
public final class InMemoryAggregateResolver implements AggregateResolver {

    private final InMemoryAggregateStore store;
    private final Set<Class<?>> supportedTypes;

    /**
     * @param store          the backing store; must not be {@code null}
     * @param supportedTypes the {@code @Aggregate} classes this resolver loads and saves; must not be {@code null}
     * @throws IllegalArgumentException if an argument or a type is {@code null}, or a type is not
     *                                  {@code @Aggregate}-annotated
     */
    public InMemoryAggregateResolver(InMemoryAggregateStore store, Collection<Class<?>> supportedTypes) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (supportedTypes == null) {
            throw new IllegalArgumentException("supportedTypes must not be null");
        }
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Class<?> type : supportedTypes) {
            requireType(type);
            if (!type.isAnnotationPresent(Aggregate.class)) {
                throw new IllegalArgumentException(type.getName() + " is not annotated @Aggregate");
            }
            types.add(type);
        }
        this.store = store;
        this.supportedTypes = Set.copyOf(types);
    }

    @Override
    public boolean supports(Class<?> aggregateType) {
        requireType(aggregateType);
        return supportedTypes.contains(aggregateType);
    }

    @Override
    public Optional<Object> load(Class<?> aggregateType, Object aggregateId) {
        requireType(aggregateType);
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId must not be null");
        }
        requireSupported(aggregateType);
        return store.get(aggregateType, aggregateId).map(Object.class::cast);
    }

    /**
     * @throws IllegalArgumentException if {@code aggregate} is {@code null} or of an unsupported type
     * @throws IllegalStateException    if its {@code @AggregateIdentifier} is missing or {@code null}
     */
    @Override
    @SuppressWarnings("unchecked")
    public void save(Object aggregate) {
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
        Class<Object> type = (Class<Object>) aggregate.getClass();
        requireSupported(type);
        store.put(type, HarnessReflection.aggregateIdentifier(aggregate), aggregate);
    }

    /** The supported types (unordered, unmodifiable). */
    public Set<Class<?>> supportedTypes() {
        return supportedTypes;
    }

    private void requireSupported(Class<?> type) {
        if (!supportedTypes.contains(type)) {
            throw new IllegalArgumentException("InMemoryAggregateResolver does not support " + type.getName());
        }
    }

    private static void requireType(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("aggregateType must not be null");
        }
    }
}
