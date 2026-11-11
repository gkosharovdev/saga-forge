package io.github.gkosharovdev.sagaforge.spring.host;

import org.springframework.beans.factory.ListableBeanFactory;

import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;

/**
 * Service-hosted handlers, loaders and savers run on their beans ({@code specs/008-spring-boot-starter} FR-011).
 */
public final class SpringHandlerInstanceProvider implements HandlerInstanceProvider {

    private final ListableBeanFactory beanFactory;

    public SpringHandlerInstanceProvider(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object instanceOf(Class<?> handlerType) {
        return beanFactory.getBean(handlerType);
    }

    /**
     * Fails unless exactly one bean has the host's type.
     *
     * @throws SagaForgeConfigurationException naming the class
     */
    public void verify(Class<?> hostType) {
        String[] names = beanFactory.getBeanNamesForType(hostType);
        if (names.length == 0) {
            throw new SagaForgeConfigurationException(hostType.getName() + " declares a @CommandHandler, @AggregateLoader"
                    + " or @AggregateSaver method but is not a bean.",
                    "Annotate it with @Component (or @Service), or declare it with a @Bean method.");
        }
        if (names.length > 1) {
            throw new SagaForgeConfigurationException(hostType.getName() + " hosts handlers but has several beans: "
                    + String.join(", ", names) + ".", "Keep exactly one bean of each handler host class.");
        }
    }
}
