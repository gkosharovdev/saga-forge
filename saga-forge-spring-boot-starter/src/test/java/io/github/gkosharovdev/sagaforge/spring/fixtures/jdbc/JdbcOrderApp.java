package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;

/**
 * The Spring idioms application (US2): a Spring Data JDBC aggregate, a prototype saga bean with an injected
 * {@link Clock}, a handler bean with a qualified parameter. Profile {@code singleton-saga} declares the saga as a
 * singleton instead, which startup refuses.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class JdbcOrderApp {

    public static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");

    @Bean
    DataSource dataSource() {
        return StarterPostgres.dataSource();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @Profile("!singleton-saga")
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    JdbcOrderSaga jdbcOrderSaga(Clock clock) {
        return new JdbcOrderSaga(clock);
    }

    @Bean
    @Profile("singleton-saga")
    JdbcOrderSaga singletonOrderSaga(Clock clock) {
        return new JdbcOrderSaga(clock);
    }

    @Bean
    ChargeService chargeService() {
        return new ChargeService();
    }

    @Bean
    PaymentProvider primaryProvider() {
        return new PaymentProvider("primary");
    }

    @Bean
    PaymentProvider backupProvider() {
        return new PaymentProvider("backup");
    }
}
