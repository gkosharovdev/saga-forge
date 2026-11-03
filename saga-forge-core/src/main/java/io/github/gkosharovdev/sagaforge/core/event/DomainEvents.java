package io.github.gkosharovdev.sagaforge.core.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;

/**
 * Static facade for recording domain events inside the current unit of work.
 *
 * <p>{@link #record(Object)} can be called from any code running on the unit of work's thread (an aggregate, a
 * domain service, a command handler) without inheriting from or implementing any Saga Forge type, so aggregates
 * may be state-stored or event-sourced. The event is noted now and takes effect when the unit of work commits;
 * on rollback it is discarded.
 *
 * <p>Saga Forge does not check that a recorded event follows a real state transition. That is the client's
 * responsibility.
 *
 * <p>{@link #capture(Runnable)} is a testing aid that lets aggregates be unit-tested without the framework.
 *
 * @see EventPublisher
 * @see DomainEventScope
 */
public final class DomainEvents {

    private DomainEvents() {
    }

    /**
     * Records {@code domainEvent} with the unit of work active on the calling thread.
     *
     * @param domainEvent a {@code @DomainEvent}-annotated object; must not be {@code null}
     * @throws IllegalArgumentException if {@code domainEvent} is {@code null} or its class is not annotated
     *                                  {@link DomainEvent @DomainEvent}
     * @throws IllegalStateException    if no unit of work is active on the calling thread (for example, a thread
     *                                  the handler started itself)
     */
    public static void record(Object domainEvent) {
        requireDomainEvent(domainEvent);
        DomainEventRecorder recorder = DomainEventScope.current()
                .orElseThrow(() -> new IllegalStateException(
                        "No unit of work is active on this thread; cannot record "
                                + domainEvent.getClass().getName()));
        recorder.record(domainEvent);
    }

    /**
     * Runs {@code action} with a temporary recorder and returns the events it recorded, in recording order.
     *
     * <p>Testing aid: lets an aggregate's behavior be asserted without the framework.
     *
     * @param action the code to run; must not be {@code null}
     * @return an unmodifiable list of the recorded events, in recording order
     * @throws IllegalArgumentException if {@code action} is {@code null}
     * @throws IllegalStateException    if a unit of work is already active on the calling thread
     */
    public static List<Object> capture(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (DomainEventScope.current().isPresent()) {
            throw new IllegalStateException(
                    "capture cannot be used while a unit of work is active on this thread");
        }
        List<Object> recorded = new ArrayList<>();
        DomainEventScope.runWith(recorded::add, action);
        return Collections.unmodifiableList(recorded);
    }

    /**
     * Checks that {@code domainEvent} is non-null and {@code @DomainEvent}-annotated.
     *
     * @param domainEvent the candidate event
     * @throws IllegalArgumentException if the check fails
     */
    private static void requireDomainEvent(Object domainEvent) {
        if (domainEvent == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        if (!domainEvent.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException(
                    domainEvent.getClass().getName() + " is not annotated @DomainEvent");
        }
    }
}
