package io.github.gkosharovdev.sagaforge.test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;

/**
 * The saga objects and handler hosts the fixture's runtime uses (FR-013): supplied instances and factories first,
 * otherwise the class's no-argument constructor. A saga factory is asked once per unit of work, as at runtime.
 */
final class FixtureObjects implements HandlerInstanceProvider, SagaObjectProvider {

    private final Map<Class<?>, Supplier<?>> sagaFactories;
    private final Map<Class<?>, Object> hosts = new LinkedHashMap<>();

    FixtureObjects(Map<Class<?>, Supplier<?>> sagaFactories, Map<Class<?>, Object> hosts) {
        this.sagaFactories = Map.copyOf(sagaFactories);
        this.hosts.putAll(hosts);
    }

    @Override
    public synchronized Object instanceOf(Class<?> handlerType) {
        if (handlerType == null) {
            throw new IllegalArgumentException("handlerType must not be null");
        }
        return hosts.computeIfAbsent(handlerType, type -> Model.newInstance(type).orElseThrow(() ->
                new IllegalStateException(type.getName() + " declares no no-argument constructor; hand the fixture an"
                        + " instance with handlers(...)")));
    }

    @Override
    public boolean supports(Class<?> sagaType) {
        if (sagaType == null) {
            throw new IllegalArgumentException("sagaType must not be null");
        }
        return sagaFactories.containsKey(sagaType) || Model.hasNoArgumentConstructor(sagaType);
    }

    @Override
    public Object newInstance(Class<?> sagaType) {
        Supplier<?> factory = sagaFactories.get(sagaType);
        if (factory != null) {
            Object saga = factory.get();
            if (!sagaType.isInstance(saga)) {
                throw new IllegalStateException("the sagaObjects factory for " + sagaType.getName() + " returned "
                        + saga);
            }
            return saga;
        }
        return Model.newInstance(sagaType).orElseThrow(() -> new IllegalStateException(sagaType.getName()
                + " declares no no-argument constructor; give the fixture sagaObjects(" + sagaType.getSimpleName()
                + ".class, ...)"));
    }
}
