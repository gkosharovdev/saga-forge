package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.lifecycle.NonStructuredStateValueException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaStateTypeMismatchException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Write-through {@link SagaContext} of one saga instance, over any {@link SagaContextStore} (FR-013).
 *
 * <p>Created per unit of work for exactly one {@code sagaId}; never shared between instances. It starts from the
 * stored {@link SagaContextRecord} (or empty) and saves a fresh record after every successful
 * {@link #putState(String, Object)} and {@link #updateStateAttribute(String, String, Object)}. The store keeps its
 * own transaction and never joins the unit of work's, so written values survive a rollback (002 FR-023, FR-024);
 * the engine creates one only after the instance's creation write committed (G-ENG-01).</p>
 *
 * <p>Value semantics are those of features 001/002: names are non-blank, {@code null} values are rejected,
 * {@link #getState(String, Class)} is type-safe ({@link SagaStateTypeMismatchException}), structured values are
 * {@code Map}s changed through {@code updateStateAttribute} ({@link NonStructuredStateValueException} for a
 * scalar). A structured value read back is an unmodifiable copy.</p>
 *
 * <p>Not thread-safe: confined to the thread of its unit of work.</p>
 */
final class EngineSagaContext implements SagaContext {

    private final String sagaId;
    private final SagaContextStore store;
    private final Clock clock;
    private final Map<String, Object> values = new LinkedHashMap<>();

    /**
     * Loads the stored values of {@code sagaId}.
     *
     * @throws IllegalArgumentException if an argument is {@code null}
     */
    EngineSagaContext(String sagaId, SagaContextStore store, Clock clock) {
        this.sagaId = requireNonNull(sagaId, "sagaId");
        this.store = requireNonNull(store, "store");
        this.clock = requireNonNull(clock, "clock");
        store.find(sagaId).ifPresent(record -> record.values().forEach(
                (name, value) -> values.put(name, mutableCopy(value))));
    }

    @Override
    public <T> Optional<T> getState(String name, Class<T> type) {
        requireNonBlank(name, "name");
        requireNonNull(type, "type");
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new SagaStateTypeMismatchException(name, type, value.getClass());
        }
        return Optional.of(type.cast(frozenCopy(value)));
    }

    @Override
    public void putState(String name, Object value) {
        requireNonBlank(name, "name");
        requireNonNull(value, "value");
        values.put(name, mutableCopy(value));
        writeThrough();
    }

    @Override
    public void updateStateAttribute(String name, String field, Object value) {
        requireNonBlank(name, "name");
        requireNonBlank(field, "field");
        requireNonNull(value, "value");
        structuredValue(name, field).put(field, value);
        writeThrough();
    }

    /** The structured value {@code name}, created empty if absent. */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> structuredValue(String name, String field) {
        Object existing = values.computeIfAbsent(name, absent -> new LinkedHashMap<>());
        if (!(existing instanceof Map<?, ?>)) {
            throw new NonStructuredStateValueException(name, field, existing.getClass());
        }
        // Safe: every stored Map is a mutable LinkedHashMap created by mutableCopy or computeIfAbsent.
        return (Map<Object, Object>) existing;
    }

    private void writeThrough() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        values.forEach((name, value) -> snapshot.put(name, frozenCopy(value)));
        store.save(new SagaContextRecord(sagaId, snapshot, clock.instant()));
    }

    private static Object mutableCopy(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<Object, Object>(map) : value;
    }

    private static Object frozenCopy(Object value) {
        return value instanceof Map<?, ?> map ? Collections.unmodifiableMap(new LinkedHashMap<>(map)) : value;
    }

    private static void requireNonBlank(String argument, String argumentName) {
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException(argumentName + " must not be null or blank");
        }
    }

    private static <T> T requireNonNull(T argument, String argumentName) {
        if (argument == null) {
            throw new IllegalArgumentException(argumentName + " must not be null");
        }
        return argument;
    }
}
