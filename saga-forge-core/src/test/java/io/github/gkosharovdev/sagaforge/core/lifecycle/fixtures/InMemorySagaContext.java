package io.github.gkosharovdev.sagaforge.core.lifecycle.fixtures;

import io.github.gkosharovdev.sagaforge.core.lifecycle.NonStructuredStateValueException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaStateTypeMismatchException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only {@link SagaContext} double used to exercise the contract. Not a production
 * implementation; none ships in {@code saga-forge-core}.
 *
 * <p>Each instance stands for exactly one saga instance and is backed by its own (non-static)
 * {@code Map<String, Object>}, so two {@code InMemorySagaContext} objects never share state (FR-010).</p>
 *
 * <p>Representation follows {@code research.md} §2: a <em>structured</em> value is a nested
 * {@link Map} stored under the name; any non-{@code Map} value is a <em>scalar</em>.</p>
 *
 * <p>Defensive copy: when {@link #putState(String, Object)} receives a {@link Map}, a shallow mutable
 * copy is stored. Later {@link #updateStateAttribute(String, String, Object)} calls therefore never
 * mutate the caller's map, and work even if the caller supplied an immutable map (e.g.
 * {@code Map.of(...)}). Non-map values are stored by reference.</p>
 */
public final class InMemorySagaContext implements SagaContext {

    private final Map<String, Object> state = new HashMap<>();

    @Override
    public <T> Optional<T> getState(String name, Class<T> type) {
        requireNonBlank(name, "name");
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        Object value = state.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new SagaStateTypeMismatchException(name, type, value.getClass());
        }
        return Optional.of(type.cast(value));
    }

    @Override
    public void putState(String name, Object value) {
        requireNonBlank(name, "name");
        requireNonNull(value, "value");
        state.put(name, value instanceof Map<?, ?> map ? new LinkedHashMap<Object, Object>(map) : value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateStateAttribute(String name, String field, Object value) {
        requireNonBlank(name, "name");
        requireNonBlank(field, "field");
        requireNonNull(value, "value");
        Object existing = state.get(name);
        if (existing == null) {
            Map<Object, Object> structured = new LinkedHashMap<>();
            structured.put(field, value);
            state.put(name, structured);
            return;
        }
        if (!(existing instanceof Map<?, ?>)) {
            throw new NonStructuredStateValueException(name, field, existing.getClass());
        }
        // Safe: every stored Map is the mutable LinkedHashMap created by this class.
        ((Map<Object, Object>) existing).put(field, value);
    }

    private static void requireNonBlank(String argument, String argumentName) {
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException(argumentName + " must not be null or blank");
        }
    }

    private static void requireNonNull(Object argument, String argumentName) {
        if (argument == null) {
            throw new IllegalArgumentException(argumentName + " must not be null");
        }
    }
}
