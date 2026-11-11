package io.github.gkosharovdev.sagaforge.spring.acceptance;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.spring.SpringSetup;
import io.github.gkosharovdev.sagaforge.spring.SpringSetup.Services;
import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaModelContributor;
import io.github.gkosharovdev.sagaforge.spring.transport.EventReceiverCustomizer;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.FlakyReceiver;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;

/**
 * The 007 acceptance application: its own DataSource, transaction manager and services; the starter assembles the
 * runtime, the model coming from a {@link SagaModelContributor} (it lives outside this package). In its own package so
 * that the auto-configuration package scan finds nothing else.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class AcceptanceApp {

    @Bean
    DataSource dataSource() {
        return StarterPostgres.dataSource();
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    Services services() {
        return SpringSetup.SERVICES.get();
    }

    @Bean
    PaymentService paymentService(Services services) {
        return services.payments();
    }

    @Bean
    ShippingService shippingService(Services services) {
        return services.shipping();
    }

    @Bean
    CardService cardService(Services services) {
        return services.cards();
    }

    @Bean
    SagaModelContributor acceptanceModel() {
        return () -> List.of(TransportAcceptanceSuite.MODEL);
    }

    /** Lets tests fail deliveries, through the starter's customizer hook. */
    @Bean
    EventReceiverCustomizer flakyDeliveries(Services services) {
        return receiver -> {
            services.receiver()[0] = new FlakyReceiver(receiver);
            return services.receiver()[0];
        };
    }
}
