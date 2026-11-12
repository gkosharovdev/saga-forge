package io.github.gkosharovdev.sagaforge.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;

/**
 * The fixture's aggregates in handler mode (research §7): keyed by type and {@code @AggregateIdentifier} value,
 * seeded with {@code givenAggregates} and read with {@code SagaFixture.aggregate}. As the runtime's
 * {@link AggregateResolver} it loads and saves the registered aggregate types that have no {@code @AggregateLoader};
 * a client loader and saver keep precedence. Writes roll back with the unit of work. A loaded aggregate is a copy, so
 * a handler that mutates it in a rolled-back unit of work leaves the stored one untouched; an aggregate class without
 * a no-argument constructor is shared instead of copied (G-009-05).
 */
final class AggregateStore implements AggregateResolver, FixtureTransactions.Participant {

    private record Key(Class<?> type, Object id) {
    }

    private final Set<Class<?>> aggregateTypes;
    private Map<Key, Object> aggregates = new LinkedHashMap<>();

    AggregateStore(Set<Class<?>> aggregateTypes) {
        this.aggregateTypes = Set.copyOf(aggregateTypes);
    }

    @Override
    public boolean supports(Class<?> aggregateType) {
        if (aggregateType == null) {
            throw new IllegalArgumentException("aggregateType must not be null");
        }
        return aggregateTypes.contains(aggregateType);
    }

    @Override
    public synchronized Optional<Object> load(Class<?> aggregateType, Object aggregateId) {
        if (aggregateType == null || aggregateId == null) {
            throw new IllegalArgumentException("aggregateType and aggregateId must not be null");
        }
        return Optional.ofNullable(aggregates.get(new Key(aggregateType, aggregateId))).map(Model::shallowCopy);
    }

    @Override
    public synchronized void save(Object aggregate) {
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
        put(Model.shallowCopy(aggregate));
    }

    synchronized void seed(Object aggregate) {
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
        if (!Model.isAggregate(aggregate.getClass())) {
            throw new IllegalArgumentException(aggregate.getClass().getName() + " is not an @Aggregate");
        }
        put(Model.shallowCopy(aggregate));
    }

    synchronized <T> Optional<T> find(Class<T> type, Object id) {
        return Optional.ofNullable(aggregates.get(new Key(type, id))).map(type::cast);
    }

    private void put(Object aggregate) {
        aggregates.put(new Key(aggregate.getClass(), Model.aggregateIdentifier(aggregate)), aggregate);
    }

    @Override
    public synchronized Object snapshot() {
        return new LinkedHashMap<>(aggregates);
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized void restore(Object snapshot) {
        aggregates = new LinkedHashMap<>((Map<Key, Object>) snapshot);
    }
}
