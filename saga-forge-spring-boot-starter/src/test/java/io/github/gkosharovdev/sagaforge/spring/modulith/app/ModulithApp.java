package io.github.gkosharovdev.sagaforge.spring.modulith.app;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;

/**
 * A Spring Modulith application with three modules ({@code specs/008-spring-boot-starter} US5): {@code orders} (the
 * saga), {@code payments} (the handler and its event) and {@code notifications} (an {@code @ApplicationModuleListener}).
 */
@SpringBootApplication(proxyBeanMethods = false)
public class ModulithApp {

    @Bean
    DataSource dataSource() {
        return StarterPostgres.dataSource();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
