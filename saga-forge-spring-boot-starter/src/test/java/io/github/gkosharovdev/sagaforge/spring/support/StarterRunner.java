package io.github.gkosharovdev.sagaforge.spring.support;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.ConcurrentSagaContextStore;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.spring.host.SpringHostConfiguration;
import io.github.gkosharovdev.sagaforge.spring.modulith.ModulithInteropConfiguration;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaModelContributor;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaRuntimeConfiguration;
import io.github.gkosharovdev.sagaforge.spring.storage.PostgresStorageConfiguration;
import io.github.gkosharovdev.sagaforge.spring.transport.InMemoryTransportConfiguration;

/** {@code ApplicationContextRunner}s over the starter's auto-configurations, without a database. */
public final class StarterRunner {

    private StarterRunner() {
    }

    /** Every Saga Forge auto-configuration of the imports file except Actuator and application events. */
    public static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                SpringTransactionConfiguration.class, ModulithInteropConfiguration.class,
                PostgresStorageConfiguration.class, InMemoryTransportConfiguration.class, SpringHostConfiguration.class,
                SagaRuntimeConfiguration.class));
    }

    /**
     * Adds storage, transactions and a transport from the runtime's in-memory doubles, and {@code model} through a
     * {@link SagaModelContributor}, so that only the model and the overrides are at stake.
     */
    public static ApplicationContextRunner withDoubles(ApplicationContextRunner runner, Class<?>... model) {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        return runner.withBean(TransactionBoundary.class, () -> boundary)
                .withBean(StorageProvider.class, () -> new TransactionalInMemoryStorageProvider(boundary))
                .withBean(SagaContextStore.class, ConcurrentSagaContextStore::new)
                .withBean(EventDispatcher.class, TestTransport::new)
                .withBean(SagaModelContributor.class, () -> () -> List.of(model));
    }
}
