package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@link EventDispatcher} that records every dispatched event in order, for the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main}; the production transports are separate modules.</p>
 *
 * <p>The harness dispatches buffered events through it only after a unit of work commits, so
 * {@link #dispatched()} is the after-commit dispatch log. An optional listener set with
 * {@link #onDispatch(Consumer)} is called with each event right after it is recorded, so the harness can
 * route an immediately dispatched event back into itself (for example to start a saga). A listener may
 * dispatch further events; they are recorded in the order the calls happen.</p>
 *
 * <p>Not thread-safe.</p>
 */
public final class RecordingEventDispatcher implements EventDispatcher {

    private final List<Object> dispatched = new ArrayList<>();
    private final Set<Class<?>> registeredSagaTypes = new LinkedHashSet<>();
    private Consumer<Object> listener;

    @Override
    public void dispatch(Object domainEvent) {
        if (domainEvent == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        dispatched.add(domainEvent);
        if (listener != null) {
            listener.accept(domainEvent);
        }
    }

    @Override
    public void registerSagaType(Class<?> sagaClass) {
        if (sagaClass == null) {
            throw new IllegalArgumentException("sagaClass must not be null");
        }
        registeredSagaTypes.add(sagaClass);
    }

    /**
     * Sets the listener called with each dispatched event after it is recorded, replacing any earlier one.
     *
     * @param listener the listener, or {@code null} to remove it
     */
    public void onDispatch(Consumer<Object> listener) {
        this.listener = listener;
    }

    /**
     * Returns every dispatched event, in dispatch order.
     *
     * @return an unmodifiable copy of the dispatch log; never {@code null}
     */
    public List<Object> dispatched() {
        return List.copyOf(dispatched);
    }

    /**
     * Returns the registered saga types, in first-registration order, without duplicates.
     *
     * @return an unmodifiable copy; never {@code null}
     */
    public List<Class<?>> registeredSagaTypes() {
        return List.copyOf(registeredSagaTypes);
    }

    /** Clears the dispatch log; registered saga types and the listener are kept. */
    public void clearDispatched() {
        dispatched.clear();
    }
}
