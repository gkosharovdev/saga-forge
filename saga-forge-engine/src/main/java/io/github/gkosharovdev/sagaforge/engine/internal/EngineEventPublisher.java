package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;

/**
 * The engine's {@link EventPublisher} (002 contracts/event-publisher.md, FR-018).
 *
 * <ul>
 *   <li>{@code null} or non-{@code @DomainEvent} event: {@link IllegalArgumentException}.</li>
 *   <li>A recorder is bound on the calling thread (an engine unit of work): exactly {@link DomainEvents#record},
 *       so the event runs in-process or goes to the after-commit handoff, and is discarded on rollback.</li>
 *   <li>Outside any unit of work: {@link EventDispatcher#dispatch} immediately. The engine does not loop the event
 *       back into itself; the transport delivers it to {@code EventReceiver.receive} (contracts/event-handoff.md
 *       last section).</li>
 * </ul>
 *
 * <p>Immutable; thread-safe if the dispatcher is.</p>
 */
final class EngineEventPublisher implements EventPublisher {

    private final EventDispatcher dispatcher;

    EngineEventPublisher(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void publish(Object domainEvent) {
        if (domainEvent == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        if (!domainEvent.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException(domainEvent.getClass().getName() + " is not annotated @DomainEvent");
        }
        if (DomainEventScope.current().isPresent()) {
            DomainEvents.record(domainEvent);
            return;
        }
        dispatcher.dispatch(domainEvent);
    }
}
