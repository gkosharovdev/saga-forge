package io.github.gkosharovdev.sagaforge.spring.transport;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.github.gkosharovdev.sagaforge.spring.telemetry.SagaForgeTelemetry;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.spring.storage.PostgresStorageConfiguration;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * The in-memory transport (007), the default when no broker transport is on the class path
 * ({@code specs/008-spring-boot-starter} FR-008): a managed executor, the transport over the {@link MessageStore}, its
 * {@link EventDispatcher} and {@link Outbox} as beans for the runtime, and the {@link TransportLifecycle} that starts it
 * around the started runtime and stops it first.
 */
@AutoConfiguration(after = { SpringTransactionConfiguration.class, PostgresStorageConfiguration.class })
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(InMemoryTransport.class)
@ConditionalOnBean({ MessageStore.class, TransactionBoundary.class })
@EnableConfigurationProperties(TransportProperties.class)
public class InMemoryTransportConfiguration {

    @Bean(name = "sagaForgeTransportExecutor")
    @ConditionalOnMissingBean(name = "sagaForgeTransportExecutor")
    public ThreadPoolTaskExecutor sagaForgeTransportExecutor(TransportProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkers());
        executor.setMaxPoolSize(properties.getWorkers());
        executor.setThreadNamePrefix("saga-forge-transport-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public InMemoryTransport sagaForgeInMemoryTransport(MessageStore messageStore, TransactionBoundary transactions,
            TransportProperties properties, ThreadPoolTaskExecutor sagaForgeTransportExecutor,
            ObjectProvider<SagaForgeTelemetry> telemetry) {
        SagaForgeTelemetry resolved = telemetry.getIfAvailable(SagaForgeTelemetry::none);
        return InMemoryTransport.builder().messageStore(messageStore).transactions(transactions)
                .settings(properties.toSettings()).executor(sagaForgeTransportExecutor)
                .openTelemetry(resolved.openTelemetry()).telemetry(resolved.settings()).build();
    }

    @Bean
    @ConditionalOnMissingBean(EventDispatcher.class)
    public EventDispatcher sagaForgeEventDispatcher(InMemoryTransport transport) {
        return transport.dispatcher();
    }

    @Bean
    @ConditionalOnMissingBean(Outbox.class)
    public Outbox sagaForgeOutbox(InMemoryTransport transport) {
        return transport.outbox();
    }

    @Bean
    @ConditionalOnMissingBean
    public TransportLifecycle sagaForgeTransportLifecycle(InMemoryTransport transport,
            ObjectProvider<SagaRuntime> runtimes, ObjectProvider<EventReceiverCustomizer> customizers,
            Environment environment) {
        Duration timeout = environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class,
                Duration.ofSeconds(30));
        return new TransportLifecycle(transport, runtimes, customizers, timeout);
    }
}
