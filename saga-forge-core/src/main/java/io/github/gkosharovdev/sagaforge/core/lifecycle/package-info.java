/**
 * Saga lifecycle and step-execution contracts.
 *
 * <p>This package defines the framework-owned contracts a future saga engine uses to drive a saga
 * instance through its lifecycle:
 *
 * <ul>
 *   <li>{@link SagaLifecycle} — process orchestration: start, advance, and complete a saga instance.
 *   <li>{@link SagaContext} — step execution: read and write named, queryable process-level state
 *       scoped to a single saga instance.
 * </ul>
 *
 * <p>Only contracts (interfaces and their supporting exception types) live here. No production
 * implementation ships in {@code saga-forge-core}; implementations are provided by the engine runtime,
 * the same posture as the {@code spi} package's {@code StorageProvider} and {@code EventDispatcher}.
 * Like the rest of {@code saga-forge-core}, this package is transport-agnostic and has zero
 * dependencies.
 */
package io.github.gkosharovdev.sagaforge.core.lifecycle;
