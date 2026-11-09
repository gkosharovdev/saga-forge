/**
 * Command gateway contracts.
 *
 * <p>This package defines the framework-owned contracts for dispatching commands to their handlers:
 *
 * <ul>
 *   <li>{@link CommandGateway} — dispatches a {@code @Command}-annotated object to the single matching
 *       {@code @CommandHandler}, synchronously and inside the caller's current saga-scoped unit of work.
 *       {@code @SagaStep} methods, services, and application code all send commands through it.
 *   <li>Handler-routing exceptions — {@link NoCommandHandlerException} when no handler exists for a
 *       command type, and {@link AggregateNotFoundException} when the target aggregate of an
 *       instance-method handler cannot be loaded.
 *   <li>{@link CommandId} — the stable idempotency identity of a command sent from a saga step, which a
 *       {@code @CommandHandler} receives by declaring a {@code CommandId} parameter.
 * </ul>
 *
 * <p>Only contracts (interfaces and their supporting value and exception types) live here. No production
 * implementation ships in {@code saga-forge-core}; implementations are provided by the engine runtime,
 * the same posture as the {@code spi} package's {@code StorageProvider} and {@code EventDispatcher}.
 * Like the rest of {@code saga-forge-core}, this package is transport-agnostic and has zero
 * dependencies.
 */
package io.github.gkosharovdev.sagaforge.core.command;
