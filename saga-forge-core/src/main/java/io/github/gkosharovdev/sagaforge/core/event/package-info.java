/**
 * Domain event recording and publishing contracts.
 *
 * <p>This package defines how domain events enter a saga-scoped unit of work:
 *
 * <ul>
 *   <li>{@link io.github.gkosharovdev.sagaforge.core.event.DomainEvents} — a static facade for recording
 *       {@code @DomainEvent}-annotated objects from any code running inside the current unit of work
 *       (aggregate, domain service, command handler), without inheriting from or implementing any Saga Forge
 *       type. Also offers a {@code capture} testing aid for unit-testing aggregates without the framework.
 *   <li>{@link io.github.gkosharovdev.sagaforge.core.event.EventPublisher} — an injectable interface for
 *       publishing domain events. Inside a unit of work it behaves like {@code DomainEvents.record}; outside
 *       one it hands the event to the {@link io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher}
 *       immediately, which is how application code starts a saga.
 * </ul>
 *
 * <p>The engine-facing binding of a recorder to the calling thread lives in the {@code spi} package
 * ({@link io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope},
 * {@link io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder}). No production
 * {@code EventPublisher} implementation ships in {@code saga-forge-core}; implementations are provided by the
 * engine runtime. Like the rest of {@code saga-forge-core}, this package is transport-agnostic and has zero
 * dependencies.
 */
package io.github.gkosharovdev.sagaforge.core.event;
