package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.ConcurrentSagaContextStore;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresStorageProvider;

/**
 * Misconfigurations stop startup with a description and an action naming what to fix
 * ({@code specs/008-spring-boot-starter} US3, SC-003, tasks.md T031).
 */
class MisconfigurationTest {

    private final ApplicationContextRunner runner = StarterRunner.runner();

    private ApplicationContextRunner withDoubles(Class<?>... model) {
        return StarterRunner.withDoubles(runner, model);
    }

    private static FailureAnalysis analysis(Throwable failure) {
        FailureAnalysis analysis = new SagaForgeConfigurationFailureAnalyzer().analyze(failure);
        return analysis != null ? analysis : new SagaRuntimeStartupFailureAnalyzer().analyze(failure);
    }

    @Test
    @DisplayName("no storage module: names the module to add")
    void noStorageModule() {
        runner.withClassLoader(new FilteredClassLoader(PostgresStorageProvider.class)).run(context -> {
            FailureAnalysis analysis = analysis(context.getStartupFailure());
            assertThat(analysis.getDescription()).contains("No Saga Forge storage module");
            assertThat(analysis.getAction()).contains("saga-forge-storage-postgres");
        });
    }

    @Test
    @DisplayName("the storage module without a DataSource: names the missing beans")
    void storageModuleWithoutDataSource() {
        runner.run(context -> {
            FailureAnalysis analysis = analysis(context.getStartupFailure());
            assertThat(analysis.getDescription()).contains("postgres").contains("DataSource");
            assertThat(analysis.getAction()).contains("spring.datasource");
        });
    }

    @Test
    @DisplayName("saga-forge.storage.type naming an absent module: names the property and the artifact")
    void selectedModuleAbsent() {
        DataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
        runner.withClassLoader(new FilteredClassLoader(PostgresStorageProvider.class))
                .withPropertyValues("saga-forge.storage.type=postgres")
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .run(context -> {
                    FailureAnalysis analysis = analysis(context.getStartupFailure());
                    assertThat(analysis.getDescription()).contains("saga-forge.storage.type is postgres")
                            .contains("saga-forge-storage-postgres");
                    assertThat(analysis.getAction()).contains("Add saga-forge-storage-postgres");
                });
    }

    @Test
    @DisplayName("an invalid saga model: every error of the report is listed")
    void invalidModel() {
        withDoubles(NoSagaId.class).run(context -> {
            FailureAnalysis analysis = analysis(context.getStartupFailure());
            assertThat(analysis.getDescription()).contains("NoSagaId");
            assertThat(analysis.getAction()).contains("Fix each error");
        });
    }

    @Test
    @DisplayName("a singleton saga bean: names the bean and suggests prototype scope")
    void singletonSagaBean() {
        withDoubles(ValidSaga.class).withBean("validSaga", ValidSaga.class, ValidSaga::new).run(context -> {
            FailureAnalysis analysis = analysis(context.getStartupFailure());
            assertThat(analysis.getDescription()).contains(ValidSaga.class.getName()).contains("'validSaga'");
            assertThat(analysis.getAction()).contains("@Scope(\"prototype\")");
        });
    }

    @Test
    @DisplayName("a handler class without a bean: names the class")
    void handlerWithoutBean() {
        withDoubles(ValidSaga.class, Handler.class).run(context -> {
            FailureAnalysis analysis = analysis(context.getStartupFailure());
            assertThat(analysis.getDescription()).contains(Handler.class.getName()).contains("is not a bean");
            assertThat(analysis.getAction()).contains("@Component");
        });
    }

    @Test
    @DisplayName("two SagaRuntime beans: names both")
    void twoRuntimes() {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        runner.withBean("runtimeA", SagaRuntime.class, () -> runtime(boundary))
                .withBean("runtimeB", SagaRuntime.class, () -> runtime(boundary)).run(context -> {
                    FailureAnalysis analysis = analysis(context.getStartupFailure());
                    assertThat(analysis.getDescription()).contains("runtimeA").contains("runtimeB");
                    assertThat(analysis.getAction()).contains("single SagaRuntime");
                });
    }

    private static SagaRuntime runtime(InMemoryTransactionBoundary boundary) {
        return SagaRuntime.builder().register(ValidSaga.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new ConcurrentSagaContextStore()).transactions(boundary).dispatcher(new TestTransport())
                .build();
    }

    /** Lacks its {@code @SagaId} field. */
    @Saga
    public static class NoSagaId {

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void on(OrderCreatedEvent event) {
            // never runs
        }
    }

    @Saga
    public static class ValidSaga {

        @io.github.gkosharovdev.sagaforge.core.annotation.SagaId
        private String sagaId;

        @StartSaga
        @io.github.gkosharovdev.sagaforge.core.annotation.EndSaga
        @SagaStep(associationProperty = "orderId")
        public void on(OrderCreatedEvent event) {
            // ends at once
        }
    }

    public static class Handler {

        @Command
        public record Ping(String id) {
        }

        @CommandHandler
        public void handle(Ping ping) {
            // never runs
        }
    }
}
