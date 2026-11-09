package io.github.gkosharovdev.sagaforge.core.event;

/**
 * Injectable entry point for publishing domain events from application code.
 *
 * <p>Typically used outside a unit of work, for example to start a saga. Distinct from the
 * {@link io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher} transport SPI.
 *
 * <ul>
 *   <li><strong>Inside a unit of work</strong>, {@link #publish(Object)} is equivalent to
 *       {@link DomainEvents#record(Object)}: the event is delivered in-process if it triggers a step of the saga
 *       instance owning the unit of work, otherwise it is delivered after commit through the after-commit handoff
 *       ({@link io.github.gkosharovdev.sagaforge.core.spi.EventHandoff}; by default
 *       {@link io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher#dispatch(Object)}) and discarded on
 *       rollback.
 *   <li><strong>Outside any unit of work</strong>, the event is handed to
 *       {@link io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher#dispatch(Object)} immediately.
 * </ul>
 *
 * <p>No implementation ships in {@code saga-forge-core}; the engine runtime provides one.
 *
 * @see DomainEvents
 */
public interface EventPublisher {

    /**
     * Publishes {@code domainEvent}, recording it with the active unit of work if there is one, or dispatching
     * it immediately otherwise.
     *
     * @param domainEvent a {@code @DomainEvent}-annotated object; must not be {@code null}
     * @throws IllegalArgumentException if {@code domainEvent} is {@code null} or its class is not annotated
     *                                  {@link io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent
     *                                  @DomainEvent}
     */
    void publish(Object domainEvent);
}
