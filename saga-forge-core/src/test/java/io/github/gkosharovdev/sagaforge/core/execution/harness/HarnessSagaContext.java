package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.lifecycle.NonStructuredStateValueException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaStateTypeMismatchException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Write-through {@link SagaContext} for the saga test harness, backed by an {@link InMemorySagaContextStore}.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <p>The harness creates one per saga instance per unit of work. It loads the instance's
 * {@link SagaContextRecord} when created, and saves a fresh record after every successful
 * {@link #putState(String, Object)} / {@link #updateStateAttribute(String, String, Object)} call. The store
 * is never snapshotted, so context writes survive a rollback of the unit of work (spec FR-023, FR-024).</p>
 *
 * <p>Value semantics are those of feature 001's {@code InMemorySagaContext}: scalars, structured values as
 * {@code Map}s, {@link SagaStateTypeMismatchException}, {@link NonStructuredStateValueException}, and
 * {@code null} rejection. A structured value read through {@link #getState(String, Class)} is an unmodifiable
 * copy: change it through {@code updateStateAttribute} or {@code putState} so the change is written through.</p>
 */
public final class HarnessSagaContext implements SagaContext {

    private final String sagaId;
    private final InMemorySagaContextStore store;
    private final Clock clock;
    private final Map<String, Object> state = new LinkedHashMap<>();

    /**
     * @param sagaId the saga instance this context is bound to; must not be {@code null}
     * @param store  where values are loaded from and written to; must not be {@code null}
     * @param clock  timestamps for {@link SagaContextRecord#lastModifiedAt()}; must not be {@code null}
     */
    public HarnessSagaContext(String sagaId, InMemorySagaContextStore store, Clock clock) {
        requireNonNull(sagaId, "sagaId");
        requireNonNull(store, "store");
        requireNonNull(clock, "clock");
        this.sagaId = sagaId;
        this.store = store;
        this.clock = clock;
        store.find(sagaId).ifPresent(record -> record.values().forEach(
                (name, value) -> state.put(name, value instanceof Map<?, ?> map ? new LinkedHashMap<>(map) : value)));
    }

    @Override
    public <T> Optional<T> getState(String name, Class<T> type) {
        requireNonBlank(name, "name");
        requireNonNull(type, "type");
        Object value = state.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new SagaStateTypeMismatchException(name, type, value.getClass());
        }
        Object view = value instanceof Map<?, ?> map ? Collections.unmodifiableMap(new LinkedHashMap<>(map)) : value;
        return Optional.of(type.cast(view));
    }

    @Override
    public void putState(String name, Object value) {
        requireNonBlank(name, "name");
        requireNonNull(value, "value");
        state.put(name, value instanceof Map<?, ?> map ? new LinkedHashMap<Object, Object>(map) : value);
        writeThrough();
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
        } else if (existing instanceof Map<?, ?>) {
            // Safe: every stored Map is a mutable LinkedHashMap created by this class.
            ((Map<Object, Object>) existing).put(field, value);
        } else {
            throw new NonStructuredStateValueException(name, field, existing.getClass());
        }
        writeThrough();
    }

    /**
     * Returns an unmodifiable, deep (one level) copy of the values stored for {@code sagaId}, or an empty map
     * if nothing was written.
     */
    static Map<String, Object> view(InMemorySagaContextStore store, String sagaId) {
        return store.find(sagaId).map(record -> frozenCopy(record.values())).orElse(Map.of());
    }

    private void writeThrough() {
        store.save(new SagaContextRecord(sagaId, frozenCopy(state), clock.instant()));
    }

    private static Map<String, Object> frozenCopy(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> copy.put(
                name, value instanceof Map<?, ?> map ? Collections.unmodifiableMap(new LinkedHashMap<>(map)) : value));
        return Collections.unmodifiableMap(copy);
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
