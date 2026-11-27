package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link OrderApp} over its own Postgres schema, named by {@code test.schema}, for the migration
 * tests ({@code specs/018-versioned-schema-migrations}).
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class MigrationApp {

  @Bean
  DataSource dataSource(@Value("${test.schema}") String schema) {
    PGSimpleDataSource dataSource = (PGSimpleDataSource) StarterPostgres.dataSource();
    dataSource.setCurrentSchema(schema);
    return dataSource;
  }

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean
  OrderRepository orderRepository() {
    return new OrderRepository();
  }

  @Bean
  PaymentService paymentService() {
    return new PaymentService();
  }

  @Bean
  OutcomeListener outcomeListener() {
    return new OutcomeListener();
  }
}
