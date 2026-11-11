package io.github.gkosharovdev.sagaforge.spring.host;

import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.await;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrder;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderApp;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.OrderPlaced;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderRepository;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.PaymentProvider;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.ambiguous.AuditService;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.idtype.Invoice;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.loader.OrderLoader;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.missing.ReportService;
import io.github.gkosharovdev.sagaforge.spring.support.StarterApp;

/** US2 ({@code specs/008-spring-boot-starter}): sagas, handlers and aggregates through Spring idioms. */
@Tag("container")
class SpringHostSpisContainerTest {

    private static final String EXTRA = "io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.";

    @BeforeEach
    void ordersTable() {
        StarterPostgres.reset();
        new ResourceDatabasePopulator(new ClassPathResource("jdbc-orders.sql")).execute(StarterPostgres.dataSource());
        StarterApp.jdbc().execute("TRUNCATE jdbc_order, jdbc_invoice");
    }

    private static ConfigurableApplicationContext start(Class<?>[] sources, String... properties) {
        return new SpringApplicationBuilder(sources).web(WebApplicationType.NONE)
                .properties("saga-forge.transport.poll-interval=50ms", StarterApp.NO_MODULITH)
                .properties(properties).run();
    }

    private static ConfigurableApplicationContext start(String... properties) {
        return start(new Class<?>[] { JdbcOrderApp.class }, properties);
    }

    @Test
    @DisplayName("saga bean with an injected Clock, qualified handler parameter, Spring Data aggregate: completes")
    void springIdioms() {
        try (ConfigurableApplicationContext context = start()) {
            JdbcOrderRepository orders = context.getBean(JdbcOrderRepository.class);
            orders.save(JdbcOrder.placed("j-1"));

            context.getBean(EventPublisher.class).publish(new OrderPlaced("j-1", BigDecimal.TEN));

            await(() -> "COMPLETED".equals(status(JdbcOrderSaga.class)), "the Spring Data saga to complete");
            JdbcOrder saved = orders.findById("j-1").orElseThrow();
            assertThat(saved.status()).isEqualTo("CONFIRMED");
            assertThat(saved.version()).isEqualTo(1L);
            assertThat(context.getBean("primaryProvider", PaymentProvider.class).charged()).hasSize(1);
            assertThat(context.getBean("backupProvider", PaymentProvider.class).charged()).isEmpty();
            Map<String, Object> values = StarterApp.jdbc().queryForMap("SELECT values::text AS v FROM saga_context");
            assertThat(values.get("v").toString()).contains(JdbcOrderApp.NOW.toString()).contains("primary");
        }
    }

    @Test
    @DisplayName("a client @AggregateLoader/@AggregateSaver takes precedence over the repository")
    void clientLoaderWins() {
        try (ConfigurableApplicationContext context = start(
                new Class<?>[] { JdbcOrderApp.class, OrderLoader.Config.class },
                "saga-forge.scan-packages=" + EXTRA + "loader")) {
            OrderLoader loader = context.getBean(OrderLoader.class);
            loader.put(JdbcOrder.placed("j-2"));

            context.getBean(EventPublisher.class).publish(new OrderPlaced("j-2", BigDecimal.ONE));

            await(() -> "COMPLETED".equals(status(JdbcOrderSaga.class)), "the saga to complete through the loader");
            assertThat(loader.loads()).isEqualTo(1);
            assertThat(loader.get("j-2").status()).isEqualTo("CONFIRMED");
            assertThat(context.getBean(JdbcOrderRepository.class).count()).isZero();
        }
    }

    @Test
    @DisplayName("a singleton saga bean is refused, naming the bean and suggesting prototype scope")
    void singletonSagaRefused() {
        assertThatThrownBy(() -> start("spring.profiles.active=singleton-saga"))
                .hasRootCauseInstanceOf(SagaForgeConfigurationException.class)
                .rootCause().hasMessageContaining("singletonOrderSaga").hasMessageContaining(JdbcOrderSaga.class.getName());
    }

    @Test
    @DisplayName("a parameter matching several beans fails startup naming the handler and parameter")
    void ambiguousParameter() {
        assertThatThrownBy(() -> start(new Class<?>[] { JdbcOrderApp.class, AuditService.Config.class },
                "saga-forge.scan-packages=" + EXTRA + "ambiguous"))
                .hasRootCauseInstanceOf(SagaRuntimeStartupException.class)
                .rootCause().hasMessageContaining("AuditService").hasMessageContaining("PaymentProvider");
    }

    @Test
    @DisplayName("a parameter no bean supplies fails startup naming the handler and parameter")
    void missingParameter() {
        assertThatThrownBy(() -> start(new Class<?>[] { JdbcOrderApp.class, ReportService.Config.class },
                "saga-forge.scan-packages=" + EXTRA + "missing"))
                .hasRootCauseInstanceOf(SagaRuntimeStartupException.class)
                .rootCause().hasMessageContaining("ReportService").hasMessageContaining("ZoneId");
    }

    @Test
    @DisplayName("an identifier type the repository id type cannot be converted from fails naming both types")
    void unconvertibleIdentifier() {
        assertThatThrownBy(() -> start(new Class<?>[] { JdbcOrderApp.class, Invoice.Config.class },
                "saga-forge.scan-packages=" + EXTRA + "idtype"))
                .hasRootCauseInstanceOf(SagaForgeConfigurationException.class)
                .rootCause().hasMessageContaining("LocalDate").hasMessageContaining("UUID");
    }
}
