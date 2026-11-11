package io.github.gkosharovdev.sagaforge.spring.runtime;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.spring.host.SpringDataAggregateResolver;
import io.github.gkosharovdev.sagaforge.spring.host.SpringHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.spring.host.SpringHostConfiguration;
import io.github.gkosharovdev.sagaforge.spring.host.SpringSagaObjectProvider;
import io.github.gkosharovdev.sagaforge.spring.storage.PostgresStorageConfiguration;
import io.github.gkosharovdev.sagaforge.spring.transport.InMemoryTransportConfiguration;

/**
 * Assembles the application's {@link SagaRuntime} ({@code specs/008-spring-boot-starter} FR-001 to FR-003, FR-014,
 * contracts/starter-configuration.md): collects the saga model, checks storage, transport and the Spring host SPIs,
 * builds the runtime (not started; {@link SagaRuntimeLifecycle} starts it), and exposes its {@link CommandGateway} and
 * {@link EventPublisher}. An application-defined {@code SagaRuntime} bean replaces the assembled one.
 */
@AutoConfiguration(after = { SpringTransactionConfiguration.class, PostgresStorageConfiguration.class,
        InMemoryTransportConfiguration.class, SpringHostConfiguration.class })
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(SagaRuntime.class)
@EnableConfigurationProperties(SagaForgeProperties.class)
public class SagaRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SagaModelCollector sagaForgeModelCollector(ConfigurableListableBeanFactory beanFactory,
            SagaForgeProperties properties, ObjectProvider<SagaModelContributor> contributors) {
        return new SagaModelCollector(beanFactory, properties.getScanPackages(), contributors);
    }

    @Bean
    @ConditionalOnMissingBean(SagaRuntime.class)
    public SagaRuntime sagaRuntime(ConfigurableListableBeanFactory beanFactory, SagaForgeProperties properties,
            SagaModelCollector collector, ObjectProvider<StorageProvider> storage,
            ObjectProvider<SagaContextStore> contextStore, ObjectProvider<TransactionBoundary> transactions,
            ObjectProvider<EventDispatcher> dispatcher, ObjectProvider<Outbox> outbox,
            ObjectProvider<SagaObjectProvider> sagaObjects, ObjectProvider<HandlerInstanceProvider> handlerInstances,
            ObjectProvider<HandlerParameterResolver> parameterResolvers,
            ObjectProvider<AggregateResolver> aggregateResolvers) {
        properties.validate();
        StorageDetection detection = new StorageDetection(beanFactory.getBeanClassLoader());
        detection.checkSelection(properties.getStorage().getType());
        StorageProvider storageProvider = storage.getIfAvailable();
        SagaContextStore contexts = contextStore.getIfAvailable();
        if (storageProvider == null || contexts == null) {
            throw detection.missingStorage();
        }
        EventDispatcher eventDispatcher = dispatcher.getIfAvailable();
        if (eventDispatcher == null) {
            throw new SagaForgeConfigurationException("No Saga Forge transport was assembled.",
                    "Keep saga-forge-transport-inmemory on the class path with a configured storage module, or define"
                            + " your own EventDispatcher and Outbox beans.");
        }
        TransactionBoundary boundary = transactions.getIfAvailable();
        if (boundary == null) {
            throw new SagaForgeConfigurationException("No TransactionBoundary is available.",
                    "Configure a DataSource and a PlatformTransactionManager, or define a TransactionBoundary bean.");
        }

        List<Class<?>> model = collector.collect();
        verify(model, sagaObjects.getIfAvailable(), handlerInstances.getIfAvailable(), aggregateResolvers);

        SagaRuntime.Builder builder = SagaRuntime.builder()
                .register(model.toArray(Class<?>[]::new))
                .storage(storageProvider)
                .contextStore(contexts)
                .transactions(boundary)
                .dispatcher(eventDispatcher)
                .maxTotalRetryBackoff(properties.getRuntime().getMaxTotalRetryBackoff());
        outbox.ifAvailable(builder::outbox);
        sagaObjects.ifAvailable(builder::sagaObjects);
        handlerInstances.ifAvailable(builder::handlerInstances);
        parameterResolvers.orderedStream().forEach(builder::parameterResolver);
        aggregateResolvers.orderedStream().forEach(builder::aggregateResolver);
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandGateway sagaForgeCommandGateway(ConfigurableListableBeanFactory beanFactory) {
        return soleRuntime(beanFactory).commandGateway();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher sagaForgeEventPublisher(ConfigurableListableBeanFactory beanFactory) {
        return soleRuntime(beanFactory).eventPublisher();
    }

    @Bean
    @ConditionalOnMissingBean
    public SagaRuntimeLifecycle sagaForgeRuntimeLifecycle(ConfigurableListableBeanFactory beanFactory) {
        return new SagaRuntimeLifecycle(soleRuntime(beanFactory));
    }

    /** @throws SagaForgeConfigurationException naming the beans if there are several runtimes (FR-003) */
    private static SagaRuntime soleRuntime(ConfigurableListableBeanFactory beanFactory) {
        String[] names = beanFactory.getBeanNamesForType(SagaRuntime.class);
        if (names.length > 1) {
            throw new SagaForgeConfigurationException("Several SagaRuntime beans are defined: " + String.join(", ", names)
                    + "; Saga Forge supports one runtime per application.", "Keep a single SagaRuntime bean.");
        }
        return beanFactory.getBean(SagaRuntime.class);
    }

    /** The checks of FR-010 to FR-013 that the runtime's own startup checks cannot see. */
    private static void verify(List<Class<?>> model, SagaObjectProvider sagaObjects,
            HandlerInstanceProvider handlerInstances, ObjectProvider<AggregateResolver> aggregateResolvers) {
        for (Class<?> type : model) {
            if (type.isAnnotationPresent(Saga.class)) {
                if (sagaObjects instanceof SpringSagaObjectProvider spring) {
                    spring.verify(type);
                }
            } else if (type.isAnnotationPresent(Aggregate.class)) {
                aggregateResolvers.orderedStream().filter(SpringDataAggregateResolver.class::isInstance)
                        .map(SpringDataAggregateResolver.class::cast).forEach(resolver -> resolver.verify(type));
            } else if (SagaModelCollector.isModelClass(type)
                    && handlerInstances instanceof SpringHandlerInstanceProvider spring) {
                spring.verify(type);
            }
        }
    }
}
