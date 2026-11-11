package io.github.gkosharovdev.sagaforge.spring.transport;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageLog;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionBoundary;
import io.github.gkosharovdev.sagaforge.spring.modulith.ModulithInteropConfiguration;
import io.github.gkosharovdev.sagaforge.spring.modulith.ModulithMessageLog;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageLog;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/** Conditions and properties of the transport's Spring wiring (contracts/spring-wiring.md, tasks.md T074). */
class InMemoryTransportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(ModulithInteropConfiguration.class, InMemoryTransportConfiguration.class));

    private ApplicationContextRunner withDatabase() {
        DataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        return runner.withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("without a DataSource and transaction manager nothing is wired")
    void nothingWithoutDatabase() {
        runner.run(context -> assertThat(context).doesNotHaveBean(InMemoryTransport.class));
    }

    @Test
    @DisplayName("with a database: Spring boundary, Postgres log, transport, lifecycle and event bridge")
    void ownLogWiring() {
        withDatabase().run(context -> {
            assertThat(context).hasSingleBean(InMemoryTransport.class);
            assertThat(context.getBean(TransactionBoundary.class)).isInstanceOf(SpringTransactionBoundary.class);
            assertThat(context.getBean(MessageLog.class)).isInstanceOf(PostgresMessageLog.class);
            assertThat(context).hasSingleBean(TransportLifecycle.class).hasSingleBean(ApplicationEventBridge.class);
        });
    }

    @Test
    @DisplayName("properties are bound; the application-event bridge can be switched off")
    void properties() {
        withDatabase().withPropertyValues("saga-forge.transport.workers=7",
                "saga-forge.transport.max-attempts=4", "saga-forge.transport.retention=3d",
                "saga-forge.transport.publish-application-events=false").run(context -> {
                    TransportProperties properties = context.getBean(TransportProperties.class);
                    assertThat(properties.toSettings().workers()).isEqualTo(7);
                    assertThat(properties.toSettings().maxAttempts()).isEqualTo(4);
                    assertThat(properties.toSettings().retention()).hasDays(3);
                    assertThat(context).doesNotHaveBean(ApplicationEventBridge.class);
                });
    }

    @Test
    @DisplayName("with Spring Modulith's registry the log is the interop log, unless switched off")
    void modulithInterop() {
        ApplicationContextRunner modulith = withDatabase()
                .withBean(EventPublicationRepository.class, () -> stub(EventPublicationRepository.class))
                .withBean(EventSerializer.class, () -> stub(EventSerializer.class));

        modulith.run(context -> assertThat(context.getBean(MessageLog.class)).isInstanceOf(ModulithMessageLog.class));
        modulith.withPropertyValues("saga-forge.transport.modulith-interop=false").run(context ->
                assertThat(context.getBean(MessageLog.class)).isInstanceOf(PostgresMessageLog.class));
    }

    @Test
    @DisplayName("a host-defined MessageLog wins")
    void hostLogWins() {
        MessageLog own = stub(MessageLog.class);
        withDatabase().withBean(MessageLog.class, () -> own)
                .run(context -> assertThat(context.getBean(MessageLog.class)).isSameAs(own));
    }

    /** An interface implementation whose methods do nothing; enough for bean conditions. */
    private static <T> T stub(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "stub " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }));
    }
}
