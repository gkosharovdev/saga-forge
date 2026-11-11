/**
 * Package-private runtime of the saga engine: the saga model, units of work, locks, and the production
 * {@code SagaContext}, {@code SagaLifecycle}, {@code CommandGateway} and {@code EventPublisher} implementations.
 *
 * <p>Not API. Nothing here is meant to be used outside {@code saga-forge-runtime}; its types may change without
 * notice. Hosts use {@link io.github.gkosharovdev.sagaforge.runtime} and the core SPI only.
 */
package io.github.gkosharovdev.sagaforge.runtime.internal;
