package io.github.gkosharovdev.sagaforge.spring.storage;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSagaContextStore;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchemaValidator;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * Postgres storage from the application's {@code DataSource} ({@code specs/008-spring-boot-starter}
 * FR-006, FR-009, FR-016): saga instances, {@code SagaContext} values and the in-memory transport's
 * message store, all joining the application's transactions. Selected when the Postgres module is
 * on the class path and {@code saga-forge.storage.type} is unset or {@code postgres}; each bean
 * gives way to an application bean of its type. The schema initializer runs after the application's
 * own database initializers (Flyway, Liquibase, {@code spring.sql.init}) and, with {@code
 * initialize-schema=migrate}, applies Saga Forge's migrations through its own Flyway instance
 * ({@code specs/018-versioned-schema-migrations}).
 */
@AutoConfiguration(after = SpringTransactionConfiguration.class)
@ConditionalOnProperty(
    prefix = "saga-forge",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@ConditionalOnClass(PostgresStorageProvider.class)
@ConditionalOnBean({DataSource.class, TransactionBoundary.class})
@ConditionalOnProperty(
    prefix = "saga-forge.storage",
    name = "type",
    havingValue = "postgres",
    matchIfMissing = true)
@EnableConfigurationProperties(SagaForgeProperties.class)
public class PostgresStorageConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @DependsOnDatabaseInitialization
  public SchemaInitializer sagaForgeSchemaInitializer(
      DataSource dataSource,
      SagaForgeProperties properties,
      ObjectProvider<SchemaMigrator> migrator,
      ResourceLoader resourceLoader) {
    SagaForgeProperties.Postgres postgres = properties.getStorage().getPostgres();
    SchemaInitializer initializer =
        new SchemaInitializer(
            dataSource,
            postgres.getInitializeSchema(),
            new PostgresSchemaValidator(dataSource),
            migrator.getIfAvailable(),
            postgres.getMigration(),
            resourceLoader.getClassLoader());
    initializer.initialize();
    return initializer;
  }

  /** Saga Forge's own Flyway instance, when Flyway and its Postgres support are present. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = {"org.flywaydb.core.Flyway", FlywaySchemaMigrator.POSTGRES_SUPPORT})
  static class FlywayMigratorConfiguration {

    @Bean
    @ConditionalOnMissingBean(SchemaMigrator.class)
    SchemaMigrator sagaForgeSchemaMigrator(
        DataSource dataSource, SagaForgeProperties properties, ResourceLoader resourceLoader) {
      return new FlywaySchemaMigrator(
          dataSource,
          properties.getStorage().getPostgres().getMigration().getHistoryTable(),
          resourceLoader.getClassLoader());
    }
  }

  @Bean
  @ConditionalOnMissingBean(StorageProvider.class)
  public PostgresStorageProvider sagaForgeStorageProvider(
      DataSource dataSource, TransactionBoundary transactions, SchemaInitializer schema) {
    return new PostgresStorageProvider(dataSource, transactions);
  }

  @Bean
  @ConditionalOnMissingBean(SagaContextStore.class)
  public PostgresSagaContextStore sagaForgeContextStore(
      DataSource dataSource, SchemaInitializer schema) {
    return new PostgresSagaContextStore(dataSource);
  }

  @Bean
  @ConditionalOnMissingBean(MessageStore.class)
  public PostgresMessageStore sagaForgeMessageStore(
      DataSource dataSource, TransactionBoundary transactions, SchemaInitializer schema) {
    return new PostgresMessageStore(
        dataSource, transactions, PostgresStorageConfiguration.class.getClassLoader());
  }
}
