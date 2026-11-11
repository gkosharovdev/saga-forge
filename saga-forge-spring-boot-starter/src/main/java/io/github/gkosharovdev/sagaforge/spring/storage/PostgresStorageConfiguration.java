package io.github.gkosharovdev.sagaforge.spring.storage;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSagaContextStore;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider;

/**
 * Postgres storage from the application's {@code DataSource} ({@code specs/008-spring-boot-starter} FR-006, FR-009,
 * FR-016): saga instances, {@code SagaContext} values and the in-memory transport's message store, all joining the
 * application's transactions. Selected when the Postgres module is on the class path and {@code saga-forge.storage.type}
 * is unset or {@code postgres}; each bean gives way to an application bean of its type.
 */
@AutoConfiguration(after = SpringTransactionConfiguration.class)
@ConditionalOnProperty(prefix = "saga-forge", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnClass(PostgresStorageProvider.class)
@ConditionalOnBean({ DataSource.class, TransactionBoundary.class })
@ConditionalOnProperty(prefix = "saga-forge.storage", name = "type", havingValue = "postgres", matchIfMissing = true)
@EnableConfigurationProperties(SagaForgeProperties.class)
public class PostgresStorageConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SchemaInitializer sagaForgeSchemaInitializer(DataSource dataSource, SagaForgeProperties properties) {
        SchemaInitializer initializer = new SchemaInitializer(dataSource,
                properties.getStorage().getPostgres().getInitializeSchema());
        initializer.initialize();
        return initializer;
    }

    @Bean
    @ConditionalOnMissingBean(StorageProvider.class)
    public PostgresStorageProvider sagaForgeStorageProvider(DataSource dataSource, TransactionBoundary transactions,
            SchemaInitializer schema) {
        return new PostgresStorageProvider(dataSource, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(SagaContextStore.class)
    public PostgresSagaContextStore sagaForgeContextStore(DataSource dataSource, SchemaInitializer schema) {
        return new PostgresSagaContextStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(MessageStore.class)
    public PostgresMessageStore sagaForgeMessageStore(DataSource dataSource, TransactionBoundary transactions,
            SchemaInitializer schema) {
        return new PostgresMessageStore(dataSource, transactions, PostgresStorageConfiguration.class.getClassLoader());
    }
}
