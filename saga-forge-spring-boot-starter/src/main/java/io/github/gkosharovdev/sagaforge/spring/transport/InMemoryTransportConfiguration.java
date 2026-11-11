package io.github.gkosharovdev.sagaforge.spring.transport;

import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageLog;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionBoundary;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageLog;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * Spring wiring of the in-memory transport only ({@code specs/007-in-memory-transport} FR-029,
 * contracts/spring-wiring.md): the host's transaction manager as {@link TransactionBoundary}, a Postgres
 * {@link MessageLog}, a Spring-managed executor, start and stop with the application context, and the optional
 * application-event bridge. The host still builds its {@code SagaEngine} bean, passing
 * {@code transport.dispatcher()} and {@code transport.outbox()}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration" })
@ConditionalOnClass(InMemoryTransport.class)
@ConditionalOnBean({ DataSource.class, PlatformTransactionManager.class })
@EnableConfigurationProperties(TransportProperties.class)
public class InMemoryTransportConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionBoundary.class)
    public SpringTransactionBoundary sagaForgeTransactionBoundary(PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new SpringTransactionBoundary(transactionManager, dataSource);
    }

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
    public InMemoryTransport sagaForgeInMemoryTransport(MessageLog log, TransactionBoundary transactions,
            TransportProperties properties, ThreadPoolTaskExecutor sagaForgeTransportExecutor) {
        return InMemoryTransport.builder().log(log).transactions(transactions).settings(properties.toSettings())
                .executor(sagaForgeTransportExecutor).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public TransportLifecycle sagaForgeTransportLifecycle(InMemoryTransport transport,
            ObjectProvider<SagaEngine> engines, ObjectProvider<EventReceiverCustomizer> customizers,
            Environment environment) {
        Duration timeout = environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class,
                Duration.ofSeconds(30));
        return new TransportLifecycle(transport, engines, customizers, timeout);
    }

    @Bean
    @ConditionalOnProperty(prefix = "saga-forge.transport", name = "publish-application-events",
            havingValue = "true", matchIfMissing = true)
    public ApplicationEventBridge sagaForgeApplicationEventBridge(InMemoryTransport transport,
            ApplicationEventPublisher publisher) {
        return new ApplicationEventBridge(transport, publisher);
    }

    /** The Postgres message log, when the Postgres module is present and no other log is defined. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(PostgresMessageLog.class)
    static class PostgresLogConfiguration {

        @Bean
        @ConditionalOnMissingBean(MessageLog.class)
        public PostgresMessageLog sagaForgeMessageLog(DataSource dataSource, TransactionBoundary transactions) {
            return new PostgresMessageLog(dataSource, transactions, InMemoryTransportConfiguration.class
                    .getClassLoader());
        }
    }
}
