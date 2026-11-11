package io.github.gkosharovdev.sagaforge.spring;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.spring.transport.EventReceiverCustomizer;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSagaContextStore;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageLogQueries;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.FlakyReceiver;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;

/**
 * A Spring Boot application context hosting the acceptance model over Testcontainers Postgres
 * (specs/007-in-memory-transport SC-008): the starter's transport configuration wires the transport; the application
 * builds its {@link SagaEngine} bean. With {@code modulith} the Spring Modulith JDBC registry is active, otherwise its
 * auto-configurations are excluded. A restart closes the context and opens a new one; the services survive it.
 */
public final class SpringSetup implements TransportAcceptanceSuite.Setup {

    /** Services shared across restarts, as an external system would be. */
    static final ThreadLocal<Services> SERVICES = new ThreadLocal<>();

    record Services(PaymentService payments, ShippingService shipping, CardService cards, FlakyReceiver[] receiver) {
    }

    private final boolean modulith;
    private final Services services = new Services(new PaymentService(), new ShippingService(), new CardService(),
            new FlakyReceiver[1]);
    private ConfigurableApplicationContext context;

    public SpringSetup(boolean modulith) {
        this.modulith = modulith;
        StarterPostgres.reset();
        open();
    }

    private void open() {
        SERVICES.set(services);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
                .properties(Map.of(
                        "saga-forge.transport.workers", "2",
                        "saga-forge.transport.max-attempts", "3",
                        "saga-forge.transport.initial-backoff", "10ms",
                        "saga-forge.transport.max-backoff", "40ms",
                        "saga-forge.transport.lease-duration", "1s",
                        "saga-forge.transport.poll-interval", "50ms",
                        "spring.modulith.events.jdbc.schema-initialization.enabled", "true",
                        "spring.autoconfigure.exclude", modulith ? ""
                                : "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration,"
                                        + "org.springframework.modulith.events.config.EventPublicationAutoConfiguration,"
                                        + "org.springframework.modulith.events.config.EventExternalizationAutoConfiguration"));
        context = builder.run();
        SERVICES.remove();
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    @Override
    public void publish(Object event) {
        context.getBean(SagaEngine.class).eventPublisher().publish(event);
    }

    @Override
    public boolean hasInstance(Class<?> sagaType, String status) {
        return !jdbc().queryForList("SELECT 1 FROM saga_instance WHERE saga_type = ? AND status = ?",
                sagaType.getName(), status).isEmpty();
    }

    @Override
    public int instanceCount(Class<?> sagaType) {
        return jdbc().queryForObject("SELECT count(*) FROM saga_instance WHERE saga_type = ?", Integer.class,
                sagaType.getName());
    }

    @Override
    public void restart() {
        context.close();
        open();
    }

    @Override
    public void failDeliveriesOf(Class<?> type, int times) {
        services.receiver()[0].fail(type, times);
    }

    @Override
    public MessageLogQueries queries() {
        return context.getBean(InMemoryTransport.class).queries();
    }

    @Override
    public PaymentService payments() {
        return services.payments();
    }

    @Override
    public CardService cards() {
        return services.cards();
    }

    @Override
    public void close() {
        context.close();
    }

    public List<Map<String, Object>> registryRows() {
        return jdbc().queryForList("SELECT * FROM event_publication");
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(StarterPostgres.dataSource());
    }

    /** The application: its own DataSource, transaction manager, services and engine. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {

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
            return SERVICES.get();
        }

        @Bean
        SagaEngine sagaEngine(DataSource dataSource, TransactionBoundary transactions, InMemoryTransport transport,
                Services services) {
            SagaEngine engine = SagaEngine.builder()
                    .register(TransportAcceptanceSuite.MODEL)
                    .storage(new PostgresStorageProvider(dataSource, transactions))
                    .contextStore(new PostgresSagaContextStore(dataSource))
                    .transactions(transactions)
                    .dispatcher(transport.dispatcher())
                    .outbox(transport.outbox())
                    .handlerInstances(new MapHandlerInstanceProvider().register(services.payments())
                            .register(services.shipping()).register(services.cards()))
                    .build();
            return engine;
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
}
