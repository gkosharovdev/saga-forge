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

import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.SchemaInitialization;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionBoundary;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.spring.modulith.ModulithInteropConfiguration;
import io.github.gkosharovdev.sagaforge.spring.modulith.ModulithMessageStore;
import io.github.gkosharovdev.sagaforge.spring.storage.PostgresStorageConfiguration;
import io.github.gkosharovdev.sagaforge.spring.storage.SchemaInitializer;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/** Conditions and properties of the transport's Spring wiring (contracts/spring-wiring.md, tasks.md T074; 008 moved the
 * boundary and the Postgres store into their own configurations). */
class InMemoryTransportConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(SpringTransactionConfiguration.class, ModulithInteropConfiguration.class,
                    PostgresStorageConfiguration.class, InMemoryTransportConfiguration.class));

    /** A data source that is never connected to: the schema initializer is replaced by one that checks nothing. */
    private ApplicationContextRunner withDatabase() {
        DataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        return runner.withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(SchemaInitializer.class, () -> new SchemaInitializer(dataSource, SchemaInitialization.NEVER));
    }

    @Test
    @DisplayName("without a DataSource and transaction manager nothing is wired")
    void nothingWithoutDatabase() {
        runner.run(context -> assertThat(context).doesNotHaveBean(InMemoryTransport.class));
    }

    @Test
    @DisplayName("with a database: Spring boundary, Postgres store, transport, its dispatcher and outbox, lifecycle")
    void ownLogWiring() {
        withDatabase().run(context -> {
            assertThat(context).hasSingleBean(InMemoryTransport.class);
            assertThat(context.getBean(TransactionBoundary.class)).isInstanceOf(SpringTransactionBoundary.class);
            assertThat(context.getBean(MessageStore.class)).isInstanceOf(PostgresMessageStore.class);
            assertThat(context).hasSingleBean(TransportLifecycle.class).hasSingleBean(EventDispatcher.class)
                    .hasSingleBean(Outbox.class);
        });
    }

    @Test
    @DisplayName("transport properties are bound")
    void properties() {
        withDatabase().withPropertyValues("saga-forge.transport.workers=7",
                "saga-forge.transport.max-attempts=4", "saga-forge.transport.retention=3d").run(context -> {
                    TransportProperties properties = context.getBean(TransportProperties.class);
                    assertThat(properties.toSettings().workers()).isEqualTo(7);
                    assertThat(properties.toSettings().maxAttempts()).isEqualTo(4);
                    assertThat(properties.toSettings().retention()).hasDays(3);
                });
    }

    @Test
    @DisplayName("with Spring Modulith's registry the store is the interop log, unless switched off")
    void modulithInterop() {
        ApplicationContextRunner modulith = withDatabase()
                .withBean(EventPublicationRepository.class, () -> stub(EventPublicationRepository.class))
                .withBean(EventSerializer.class, () -> stub(EventSerializer.class));

        modulith.run(context -> assertThat(context.getBean(MessageStore.class)).isInstanceOf(ModulithMessageStore.class));
        modulith.withPropertyValues("saga-forge.transport.modulith-interop=false").run(context ->
                assertThat(context.getBean(MessageStore.class)).isInstanceOf(PostgresMessageStore.class));
    }

    @Test
    @DisplayName("a host-defined MessageStore wins")
    void hostLogWins() {
        MessageStore own = stub(MessageStore.class);
        withDatabase().withBean(MessageStore.class, () -> own)
                .run(context -> assertThat(context.getBean(MessageStore.class)).isSameAs(own));
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
