package io.github.gkosharovdev.sagaforge.spring.host;

import java.lang.reflect.Field;
import java.util.Optional;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.repository.support.DefaultRepositoryInvokerFactory;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.repository.support.RepositoryInvokerFactory;
import org.springframework.util.ClassUtils;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;

/**
 * Loads and saves {@code @Aggregate}s through their Spring Data repositories ({@code specs/008-spring-boot-starter}
 * FR-013, research §4): {@code findById} with the identifier converted to the repository's id type, and {@code save},
 * in the unit of work's transaction. Client {@code @AggregateLoader}/{@code @AggregateSaver} methods keep precedence
 * (the runtime consults them first). Works for any Spring Data module.
 */
public final class SpringDataAggregateResolver implements AggregateResolver {

    private final ListableBeanFactory beanFactory;
    private final ConversionService conversionService;
    private volatile Repositories repositories;
    private volatile RepositoryInvokerFactory invokers;

    public SpringDataAggregateResolver(ListableBeanFactory beanFactory, ConversionService conversionService) {
        this.beanFactory = beanFactory;
        this.conversionService = conversionService;
    }

    @Override
    public boolean supports(Class<?> aggregateType) {
        return repositories().hasRepositoryFor(aggregateType);
    }

    @Override
    public Optional<Object> load(Class<?> aggregateType, Object aggregateId) {
        return invokers().getInvokerFor(aggregateType).invokeFindById(aggregateId).map(Object.class::cast);
    }

    @Override
    public void save(Object aggregate) {
        invokers().getInvokerFor(ClassUtils.getUserClass(aggregate)).invokeSave(aggregate);
    }

    /**
     * Fails if the aggregate's {@code @AggregateIdentifier} type cannot be converted to its repository's id type.
     *
     * @throws SagaForgeConfigurationException naming the aggregate and both types
     */
    public void verify(Class<?> aggregateType) {
        if (!supports(aggregateType)) {
            return;
        }
        Class<?> idType = repositories().getRepositoryInformationFor(aggregateType).map(info -> info.getIdType())
                .orElse(null);
        Class<?> identifierType = identifierType(aggregateType);
        if (idType != null && identifierType != null && !idType.isAssignableFrom(identifierType)
                && !conversionService.canConvert(identifierType, idType)) {
            throw new SagaForgeConfigurationException("Aggregate " + aggregateType.getName() + " has an @AggregateIdentifier"
                    + " of type " + identifierType.getName() + ", but its Spring Data repository uses id type "
                    + idType.getName() + " and no conversion exists.",
                    "Use the same type for the @AggregateIdentifier field and the repository id, or register a converter.");
        }
    }

    private static Class<?> identifierType(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(AggregateIdentifier.class)) {
                    return field.getType();
                }
            }
        }
        return null;
    }

    private Repositories repositories() {
        Repositories current = repositories;
        if (current == null) {
            current = new Repositories(beanFactory);
            repositories = current;
        }
        return current;
    }

    private RepositoryInvokerFactory invokers() {
        RepositoryInvokerFactory current = invokers;
        if (current == null) {
            current = new DefaultRepositoryInvokerFactory(repositories(), conversionService);
            invokers = current;
        }
        return current;
    }
}
