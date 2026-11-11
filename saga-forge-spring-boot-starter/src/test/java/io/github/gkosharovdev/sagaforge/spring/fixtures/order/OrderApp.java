package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import javax.sql.DataSource;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;

/**
 * A Spring Boot application with the canonical saga and no Saga Forge assembly code
 * ({@code specs/008-spring-boot-starter} US1): only the application's own beans.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class OrderApp {

    @Bean
    DataSource dataSource() {
        return StarterPostgres.dataSource();
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
}
