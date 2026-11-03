/**
 * Saga lifecycle and step-execution contracts.
 *
 * <p>This package defines the framework-owned contracts a future saga engine uses to drive a saga
 * instance through its lifecycle:
 *
 * <ul>
 *   <li>{@link SagaLifecycle} — process orchestration: start a saga instance, record completed steps,
 *       and advance, complete, or fail it.
 *   <li>{@link SagaContext} — step execution: read and write named, queryable process-level state
 *       scoped to a single saga instance. The values are persisted as a separate
 *       {@code SagaContextRecord} through the {@code spi} package's {@code SagaContextStore}, not in
 *       {@code SagaInstance.state}, and survive the rollback of a saga-scoped unit of work.
 * </ul>
 *
 * <p>Only contracts (interfaces and their supporting exception types) live here. No production
 * implementation ships in {@code saga-forge-core}; implementations are provided by the engine runtime,
 * the same posture as the {@code spi} package's {@code StorageProvider} and {@code EventDispatcher}.
 * Like the rest of {@code saga-forge-core}, this package is transport-agnostic and has zero
 * dependencies.
 */
package io.github.gkosharovdev.sagaforge.core.lifecycle;
