package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Map-backed {@link HandlerInstanceProvider} for the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main}; the Spring Boot starter backs the SPI with the application context.</p>
 *
 * <p>Instances are keyed by their exact runtime class: {@link #instanceOf(Class)} finds an instance only
 * under the class it was registered as. Registering a second instance of the same class replaces the
 * first. Not thread-safe.</p>
 */
public final class MapHandlerInstanceProvider implements HandlerInstanceProvider {

    private final Map<Class<?>, Object> instances = new HashMap<>();

    /**
     * Registers {@code instance} under its runtime class, replacing any earlier instance of that class.
     *
     * @param instance the handler service instance; must not be {@code null}
     * @return this provider, for chaining
     * @throws IllegalArgumentException if {@code instance} is {@code null}
     */
    public MapHandlerInstanceProvider register(Object instance) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        instances.put(instance.getClass(), instance);
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if no instance of {@code handlerType} was registered
     */
    @Override
    public Object instanceOf(Class<?> handlerType) {
        if (handlerType == null) {
            throw new IllegalArgumentException("handlerType must not be null");
        }
        Object instance = instances.get(handlerType);
        if (instance == null) {
            throw new IllegalStateException("no handler instance registered for " + handlerType.getName());
        }
        return instance;
    }
}
