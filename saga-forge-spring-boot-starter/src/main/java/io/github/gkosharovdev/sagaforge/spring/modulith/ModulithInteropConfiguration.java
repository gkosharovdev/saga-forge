package io.github.gkosharovdev.sagaforge.spring.modulith;

import java.lang.System.Logger.Level;
import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;

import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.spring.storage.PostgresStorageConfiguration;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * Spring Modulith interop ({@code specs/007-in-memory-transport} FR-028): when the event publication registry is
 * present, the transport's {@link MessageStore} is a {@link ModulithMessageStore}, so the application has one outbox.
 * Switched off with {@code saga-forge.transport.modulith-interop=false}.
 */
@AutoConfiguration(before = PostgresStorageConfiguration.class, after = SpringTransactionConfiguration.class, afterName = {
        "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration",
        "org.springframework.modulith.events.config.EventPublicationAutoConfiguration",
        "org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration",
        "org.springframework.modulith.events.jackson2.Jackson2EventSerializationConfiguration" })
@ConditionalOnClass({ EventPublicationRepository.class, InMemoryTransport.class, PostgresMessageStore.class })
@ConditionalOnBean({ EventPublicationRepository.class, EventSerializer.class, DataSource.class,
        TransactionBoundary.class })
@ConditionalOnProperty(prefix = "saga-forge.transport", name = "modulith-interop", havingValue = "true",
        matchIfMissing = true)
public class ModulithInteropConfiguration {

    private static final System.Logger LOGGER = System.getLogger("io.github.gkosharovdev.sagaforge.transport");

    @Bean
    @ConditionalOnMissingBean(MessageStore.class)
    public ModulithMessageStore sagaForgeMessageStore(EventPublicationRepository registry, EventSerializer serializer,
            DataSource dataSource, TransactionBoundary transactions, Environment environment) {
        if (environment.getProperty("spring.modulith.events.republish-outstanding-events-on-restart", Boolean.class,
                false)) {
            LOGGER.log(Level.INFO, "saga.transport.modulithInterop republish-outstanding-events-on-restart is on;"
                    + " Saga Forge messages are excluded (no listener for " + ModulithMessageStore.TARGET + ")");
        }
        ClassLoader loader = ModulithInteropConfiguration.class.getClassLoader();
        return new ModulithMessageStore(registry, serializer, new PostgresMessageStore(dataSource, transactions, loader),
                loader, Clock.systemUTC());
    }
}
