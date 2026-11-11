package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * SPI for outbound pub/sub event delivery: the engine hands an event to the transport, which delivers it to the
 * {@link EventReceiver}s that consume it.
 *
 * <p>This interface is <strong>transport-agnostic</strong>. It makes no assumptions about
 * whether the event publisher and the target saga instance reside in the same JVM process.
 * Implementations may deliver events in-process (e.g., over Spring's
 * {@code ApplicationEventPublisher} for single-instance deployments) or across instances via
 * a message broker (e.g., Kafka for distributed deployments). The publisher is completely
 * decoupled from the saga instance location — it is unaware of whether the target saga is
 * co-located or remote.</p>
 *
 * <p>This locality decoupling mirrors how {@link StorageProvider} decouples persistence
 * from the storage backend. Concrete transport implementations
 * ({@code saga-forge-transport-inmemory}, {@code saga-forge-transport-kafka}) are separate
 * modules that depend only on {@code saga-forge-core} and the respective transport client
 * library.</p>
 *
 * <h3>Outbound only</h3>
 * <p>The dispatcher routes nothing to saga instances. Routing and correlation belong to the engine: a transport
 * delivers every event to {@link EventReceiver#receive(Object)}, and the engine resolves the saga types and
 * instances it triggers.</p>
 *
 * <h3>Relationship to the saga-scoped unit of work</h3>
 * <p>An event recorded with
 * {@link io.github.gkosharovdev.sagaforge.core.event.DomainEvents#record(Object)} (or published
 * through {@link io.github.gkosharovdev.sagaforge.core.event.EventPublisher}, which inside a unit
 * of work is equivalent) that does not trigger a step of the saga instance owning the unit of work
 * is buffered and handed on only after that unit of work commits, through the after-commit outbox
 * ({@link Outbox}), whose default calls {@link #dispatch(Object)}; the buffer is discarded on
 * rollback. The same applies to events recorded in the non-saga unit of work that
 * {@code CommandGateway.send} opens when called outside any unit of work. Events that do trigger a
 * step of the owning instance (same event type, same {@code associationProperty} value) are
 * delivered in-process within the same unit of work and never reach this SPI. Only an event
 * published through {@code EventPublisher} outside any unit of work is handed to
 * {@link #dispatch(Object)} immediately.</p>
 *
 * <p>Implementations therefore never observe an event whose unit of work rolled back.</p>
 *
 * <h3>Implementor concerns</h3>
 * <ul>
 *   <li><strong>Serialization</strong>: Distributed implementations must handle serialization
 *       and deserialization of domain event objects for cross-instance transport.</li>
 *   <li><strong>Subscriptions</strong>: {@link #registerSagaType(Class)} tells the transport which
 *       saga types, and so which event types, the engine consumes, for example to subscribe to
 *       the right topics.</li>
 *   <li><strong>Delivery semantics</strong>: Implementations should document their delivery
 *       guarantees (at-least-once, exactly-once). The SPI does not prescribe a specific
 *       delivery semantic — this is a transport-level concern.</li>
 * </ul>
 *
 * <h3>Null-safety contracts</h3>
 * <ul>
 *   <li>{@link #dispatch(Object)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code domainEvent} is {@code null}.</li>
 *   <li>{@link #registerSagaType(Class)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code sagaClass} is {@code null}.</li>
 * </ul>
 */
public interface EventDispatcher {

    /**
     * Hands a domain event to the transport for delivery to the {@link EventReceiver}s that consume it.
     *
     * @param domainEvent the domain event to dispatch; must not be {@code null}
     * @throws IllegalArgumentException if {@code domainEvent} is {@code null}
     */
    void dispatch(Object domainEvent);

    /**
     * Tells the transport that the engine consumes events of the given saga type.
     *
     * <p>The engine calls it once per registered saga type when it starts, before it accepts the first event.
     * Implementations may use the saga class's {@code @SagaStep} methods to learn which event types to subscribe
     * to. A thrown exception refuses the engine's start.</p>
     *
     * @param sagaClass the {@code @Saga}-annotated class to register; must not be {@code null}
     * @throws IllegalArgumentException if {@code sagaClass} is {@code null}
     */
    void registerSagaType(Class<?> sagaClass);
}
