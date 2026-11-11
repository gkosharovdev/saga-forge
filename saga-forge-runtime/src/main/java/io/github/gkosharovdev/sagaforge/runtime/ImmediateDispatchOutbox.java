package io.github.gkosharovdev.sagaforge.runtime;

import java.util.ArrayList;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;

/**
 * The default {@link Outbox}: not a durable outbox, but one that dispatches each committed event to
 * {@link EventDispatcher#dispatch(Object)} right after commit ({@link Timing#AFTER_COMMIT};
 * specs/004-saga-engine contracts/event-handoff.md, FR-030; named {@code DispatchingEventHandoff} before 007).
 *
 * <p>Events are dispatched in the batch's order. A failing {@code dispatch} does not stop the others: every event
 * is attempted, and at the end the first failure is thrown with the later ones as suppressed exceptions.</p>
 *
 * <p><strong>At most once.</strong> The events of a committed unit of work are lost if the process stops before or
 * during this call, and nothing redelivers them (G-ENG-07). A transactional outbox behind the
 * {@link Timing#IN_TRANSACTION} timing closes that window.</p>
 *
 * <p>Immutable; thread-safe if the dispatcher is.</p>
 */
public final class ImmediateDispatchOutbox implements Outbox {

    private final EventDispatcher dispatcher;

    /**
     * @param dispatcher receives every outgoing event
     * @throws IllegalArgumentException if {@code dispatcher} is {@code null}
     */
    public ImmediateDispatchOutbox(EventDispatcher dispatcher) {
        if (dispatcher == null) {
            throw new IllegalArgumentException("dispatcher must not be null");
        }
        this.dispatcher = dispatcher;
    }

    /**
     * Dispatches every event of {@code batch}, in order.
     *
     * @throws IllegalArgumentException if {@code batch} is {@code null}
     * @throws RuntimeException         the first {@code dispatch} failure, the others suppressed, after every
     *                                  event was attempted
     */
    @Override
    public void store(OutgoingMessages batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        List<RuntimeException> failures = new ArrayList<>();
        for (Object event : batch.events()) {
            try {
                dispatcher.dispatch(event);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            throw firstWithOthersSuppressed(failures);
        }
    }

    private static RuntimeException firstWithOthersSuppressed(List<RuntimeException> failures) {
        RuntimeException first = failures.get(0);
        failures.stream().skip(1).filter(failure -> failure != first).forEach(first::addSuppressed);
        return first;
    }
}
