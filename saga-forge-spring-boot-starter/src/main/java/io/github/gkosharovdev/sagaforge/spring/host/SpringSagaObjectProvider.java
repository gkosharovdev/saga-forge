package io.github.gkosharovdev.sagaforge.spring.host;

import java.lang.reflect.Constructor;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;

/**
 * Saga objects from the application context ({@code specs/008-spring-boot-starter} FR-010): a {@code @Saga} class
 * that is a bean is obtained from the context for every unit of work, with its dependencies injected; it must be
 * prototype-scoped. A saga class without a bean is built through its no-argument constructor, as the runtime does
 * without a provider.
 */
public final class SpringSagaObjectProvider implements SagaObjectProvider {

    private final ConfigurableListableBeanFactory beanFactory;

    public SpringSagaObjectProvider(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public boolean supports(Class<?> sagaType) {
        return hasBean(sagaType) || noArgumentConstructor(sagaType) != null;
    }

    @Override
    public Object newInstance(Class<?> sagaType) {
        if (hasBean(sagaType)) {
            return beanFactory.getBean(sagaType);
        }
        try {
            return noArgumentConstructor(sagaType).newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not create saga " + sagaType.getName(), e);
        }
    }

    private boolean hasBean(Class<?> sagaType) {
        return beanFactory.getBeanNamesForType(sagaType, true, false).length > 0;
    }

    private static Constructor<?> noArgumentConstructor(Class<?> sagaType) {
        try {
            Constructor<?> constructor = sagaType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    /**
     * Fails if a saga bean is not prototype-scoped or several beans have the saga's type.
     *
     * @throws SagaForgeConfigurationException naming the bean
     */
    public void verify(Class<?> sagaType) {
        String[] names = beanFactory.getBeanNamesForType(sagaType, true, false);
        if (names.length > 1) {
            throw new SagaForgeConfigurationException("Saga " + sagaType.getName() + " has several beans: "
                    + String.join(", ", names) + ".", "Define at most one prototype-scoped bean per saga class.");
        }
        if (names.length == 1 && !beanFactory.getMergedBeanDefinition(names[0]).isPrototype()) {
            throw new SagaForgeConfigurationException("Saga " + sagaType.getName() + " is defined as bean '" + names[0]
                    + "' with a scope other than prototype; a saga object is created for every unit of work.",
                    "Annotate the saga class with @Scope(\"prototype\"), or do not declare it as a bean.");
        }
    }
}
