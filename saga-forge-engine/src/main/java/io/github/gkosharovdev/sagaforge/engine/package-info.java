/**
 * Public API of the saga engine runtime.
 *
 * <p>The engine drives saga instances over the {@code saga-forge-core} SPI contracts: a transport hands it events
 * through {@code EventReceiver}, it routes them to saga types and instances, runs each unit of work in the host's
 * local transaction through {@code TransactionBoundary}, persists through {@code StorageProvider} and
 * {@code SagaContextStore}, and hands committed events on through {@code Outbox}.
 *
 * <ul>
 *   <li>{@code SagaEngine} — the runtime and its builder; implements {@code EventReceiver}.
 *   <li>{@code StartupReport} — every model and engine error found by {@code SagaEngine.start()}.
 *   <li>{@code SagaEngineStartupException} — thrown by a refused start, carrying the report.
 *   <li>{@code ImmediateDispatchOutbox} — the default after-commit outbox over {@code EventDispatcher.dispatch}.
 * </ul>
 *
 * <p>This module depends on the JDK and {@code saga-forge-core} only. No Spring, JDBC, datastore or transport type
 * appears in it (Constitution Principle I); host integrations plug in through the core SPI. Application developers
 * using the Spring Boot starter never assemble an engine themselves.
 */
package io.github.gkosharovdev.sagaforge.engine;
