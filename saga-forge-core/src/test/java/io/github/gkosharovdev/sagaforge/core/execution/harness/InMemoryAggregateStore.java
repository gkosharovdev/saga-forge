package io.github.gkosharovdev.sagaforge.core.execution.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * In-memory store of aggregate instances keyed by (aggregate type, identifier), for the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main}; the production engine resolves aggregates through the host's repositories.</p>
 *
 * <h3>Snapshot and restore</h3>
 * <p>The harness takes a {@link #snapshot()} when a unit of work opens and calls {@link #restore(Object)}
 * on rollback. Aggregates are mutable objects that steps change in place, so copying the map alone is not
 * enough to undo those changes. Register a copier per aggregate type with
 * {@link #registerCopier(Class, UnaryOperator)}: the snapshot stores a copy made by it, and restore puts a
 * fresh copy of that copy back, so a snapshot can be restored more than once.</p>
 *
 * <p>For a type without a copier the store keeps <em>references</em>: restore undoes additions, removals
 * and replacements of that type's entries, but not in-place mutations. Register a copier for every
 * aggregate type whose in-place changes must roll back.</p>
 *
 * <p>Keys are exact: an entry stored under a type is found only under that same type. Entries are kept in
 * insertion order. Not thread-safe.</p>
 */
public final class InMemoryAggregateStore {

    private final Map<Key, Object> aggregates = new LinkedHashMap<>();
    private final Map<Class<?>, UnaryOperator<Object>> copiers = new HashMap<>();

    /**
     * Registers how to copy aggregates of {@code type} for snapshots. Replaces any earlier copier.
     *
     * @param type   the aggregate type; must not be {@code null}
     * @param copier returns an independent copy of its argument; must not be {@code null}
     * @param <T>    the aggregate type
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> void registerCopier(Class<T> type, UnaryOperator<T> copier) {
        requireType(type);
        if (copier == null) {
            throw new IllegalArgumentException("copier must not be null");
        }
        copiers.put(type, value -> copier.apply((T) value));
    }

    /**
     * Stores {@code aggregate} under ({@code type}, {@code id}), replacing any earlier entry.
     *
     * @throws IllegalArgumentException if any argument is {@code null} or {@code aggregate} is not an
     *                                  instance of {@code type}
     */
    public <T> void put(Class<T> type, Object id, T aggregate) {
        requireType(type);
        requireId(id);
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
        if (!type.isInstance(aggregate)) {
            throw new IllegalArgumentException(
                    "aggregate " + aggregate.getClass().getName() + " is not an instance of " + type.getName());
        }
        aggregates.put(new Key(type, id), aggregate);
    }

    /**
     * Returns the aggregate stored under ({@code type}, {@code id}).
     *
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public <T> Optional<T> get(Class<T> type, Object id) {
        requireType(type);
        requireId(id);
        return Optional.ofNullable(type.cast(aggregates.get(new Key(type, id))));
    }

    /**
     * Removes the aggregate stored under ({@code type}, {@code id}), if any.
     *
     * @return {@code true} if an entry was removed
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public boolean remove(Class<?> type, Object id) {
        requireType(type);
        requireId(id);
        return aggregates.remove(new Key(type, id)) != null;
    }

    /**
     * Returns every aggregate stored under {@code type}, in insertion order.
     *
     * @throws IllegalArgumentException if {@code type} is {@code null}
     */
    public <T> List<T> all(Class<T> type) {
        requireType(type);
        List<T> result = new ArrayList<>();
        for (Map.Entry<Key, Object> entry : aggregates.entrySet()) {
            if (entry.getKey().type().equals(type)) {
                result.add(type.cast(entry.getValue()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Captures the current contents, copying each aggregate with its type's registered copier.
     *
     * @return an opaque snapshot, accepted only by {@link #restore(Object)} of this class
     */
    public Object snapshot() {
        return new Snapshot(copyEntries(aggregates));
    }

    /**
     * Replaces the contents with a fresh copy of a previously captured snapshot.
     *
     * @param snapshot a value returned by {@link #snapshot()}
     * @throws IllegalArgumentException if {@code snapshot} is {@code null} or was not produced by this class
     */
    public void restore(Object snapshot) {
        if (!(snapshot instanceof Snapshot s)) {
            throw new IllegalArgumentException("not an InMemoryAggregateStore snapshot: " + snapshot);
        }
        Map<Key, Object> restored = copyEntries(s.aggregates());
        aggregates.clear();
        aggregates.putAll(restored);
    }

    /**
     * Returns a new, independent store with the same registered copiers and a copy of every entry, made by
     * its type's copier, in the same order. For a type without a copier the copy shares the aggregate
     * <em>references</em>, so in-place mutations of such aggregates stay visible through both stores; register
     * a copier for every mutable aggregate type that must not be shared.
     *
     * @return a new store; never {@code null}
     */
    public InMemoryAggregateStore copy() {
        InMemoryAggregateStore copy = new InMemoryAggregateStore();
        copy.copiers.putAll(copiers);
        copy.aggregates.putAll(copyEntries(aggregates));
        return copy;
    }

    private Map<Key, Object> copyEntries(Map<Key, Object> source) {
        Map<Key, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<Key, Object> entry : source.entrySet()) {
            UnaryOperator<Object> copier = copiers.getOrDefault(entry.getKey().type(), UnaryOperator.identity());
            copy.put(entry.getKey(), copier.apply(entry.getValue()));
        }
        return copy;
    }

    private static void requireType(Class<?> type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
    }

    private static void requireId(Object id) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
    }

    private record Key(Class<?> type, Object id) {
    }

    private record Snapshot(Map<Key, Object> aggregates) {
    }
}
