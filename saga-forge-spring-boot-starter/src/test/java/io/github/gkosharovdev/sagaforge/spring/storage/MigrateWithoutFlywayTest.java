package io.github.gkosharovdev.sagaforge.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.spring.diagnostics.SagaForgeConfigurationFailureAnalyzer;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code initialize-schema=migrate} without Flyway, or without its Postgres support, fails startup
 * naming the property and the missing artifacts ({@code specs/018-versioned-schema-migrations}
 * T026). No database is reached: the check comes first.
 */
class MigrateWithoutFlywayTest {

  private static ApplicationContextRunner runner(FilteredClassLoader classLoader) {
    // Never connected: the initializer fails before it opens a connection.
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL("jdbc:postgresql://localhost:1/none");
    return StarterRunner.runner()
        .withClassLoader(classLoader)
        .withBean(DataSource.class, () -> dataSource)
        .withBean(
            PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
        .withPropertyValues("saga-forge.storage.postgres.initialize-schema=migrate");
  }

  private static FailureAnalysis analysis(Throwable failure) {
    FailureAnalysis analysis = new SagaForgeConfigurationFailureAnalyzer().analyze(failure);
    assertThat(analysis).isNotNull();
    return analysis;
  }

  @Test
  @DisplayName("without flyway-core: names both artifacts")
  void withoutFlyway() {
    runner(new FilteredClassLoader(Flyway.class))
        .run(
            context -> {
              assertThat(context).hasFailed();
              FailureAnalysis analysis = analysis(context.getStartupFailure());
              assertThat(analysis.getDescription())
                  .contains("saga-forge.storage.postgres.initialize-schema=migrate needs Flyway")
                  .contains(SchemaInitializer.FLYWAY_CORE);
              assertThat(analysis.getAction()).contains("initialize-schema=validate");
            });
  }

  @Test
  @DisplayName("without flyway-database-postgresql: names that artifact only")
  void withoutPostgresSupport() {
    runner(new FilteredClassLoader(FlywaySchemaMigrator.POSTGRES_SUPPORT))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(analysis(context.getStartupFailure()).getDescription())
                  .contains(SchemaInitializer.FLYWAY_POSTGRES)
                  .doesNotContain(SchemaInitializer.FLYWAY_CORE);
            });
  }
}
