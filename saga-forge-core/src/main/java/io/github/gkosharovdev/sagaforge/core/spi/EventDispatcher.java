package io.github.gkosharovdev.sagaforge.core.spi;

/**
 * SPI for pub/sub event delivery to saga instances.
 *
 * <p>This interface is <strong>transport-agnostic</strong>. It makes no assumptions about
 * whether the event publisher and the target saga instance reside in the same JVM process.
 * Implementations may deliver events in-process (e.g., direct method invocation for
 * single-instance deployments) or across instances via a message broker (e.g., Kafka,
 * RabbitMQ for distributed deployments). The publisher is completely decoupled from the
 * saga instance location — it is unaware of whether the target saga is co-located or
 * remote.</p>
 *
 * <p>This locality decoupling mirrors how {@link StorageProvider} decouples persistence
 * from the storage backend. Concrete transport implementations
 * ({@code saga-forge-transport-inmemory}, {@code saga-forge-transport-kafka},
 * {@code saga-forge-transport-rabbitmq}) are separate modules that depend only on
 * {@code saga-forge-core} and the respective transport client library.</p>
 *
 * <h3>Implementor concerns</h3>
 * <ul>
 *   <li><strong>Serialization</strong>: Distributed implementations must handle serialization
 *       and deserialization of domain event objects for cross-instance transport.</li>
 *   <li><strong>Event routing</strong>: Implementations must route events to the correct saga
 *       instance based on association property correlation, regardless of which application
 *       instance hosts that saga.</li>
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
     * Dispatches a domain event to all saga instances whose association property value
     * matches the corresponding field value in the event.
     *
     * <p>The implementation is responsible for resolving which saga instances (if any)
     * should receive this event based on {@code @SagaStep} association property
     * correlation. If the event matches a {@code @StartSaga} step and no existing
     * instance correlates, a new saga instance may be created.</p>
     *
     * @param domainEvent the domain event to dispatch; must not be {@code null}
     * @throws IllegalArgumentException if {@code domainEvent} is {@code null}
     */
    void dispatch(Object domainEvent);

    /**
     * Registers a saga type so the dispatcher can route events to instances of that saga.
     *
     * <p>Implementations use the registered saga class to discover {@code @SagaStep}
     * methods and their association properties, enabling correct event-to-saga routing.
     * This method is typically called at startup by the saga engine during annotation
     * scanning.</p>
     *
     * @param sagaClass the {@code @Saga}-annotated class to register; must not be {@code null}
     * @throws IllegalArgumentException if {@code sagaClass} is {@code null}
     */
    void registerSagaType(Class<?> sagaClass);
}
