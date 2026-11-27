package io.github.gkosharovdev.sagaforge.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.spring.fixtures.order.MigrationApp;
import io.github.gkosharovdev.sagaforge.spring.support.StarterApp;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * An application without Flyway starts with {@code never}, {@code always} and {@code validate}
 * ({@code specs/018-versioned-schema-migrations} FR-018): Flyway is hidden from the application's
 * class loader, so neither Spring Boot's Flyway nor Saga Forge's migrator is configured.
 */
@Tag("container")
class StartWithoutFlywayTest {

  @ParameterizedTest(name = "initialize-schema={0}")
  @ValueSource(strings = {"never", "always", "validate"})
  @DisplayName("starts without Flyway on the class path")
  void startsWithoutFlyway(String mode) {
    String schema = MigrationSchemas.create();
    if (!mode.equals("always")) {
      MigrationSchemas.run(schema, "/" + PostgresSchema.SCHEMA_SCRIPT);
    }
    FilteredClassLoader withoutFlyway = new FilteredClassLoader(Flyway.class);

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(MigrationApp.class)
            .resourceLoader(new DefaultResourceLoader(withoutFlyway))
            .web(WebApplicationType.NONE)
            .run(
                "--test.schema=" + schema,
                "--saga-forge.storage.postgres.initialize-schema=" + mode,
                "--" + StarterApp.NO_MODULITH)) {
      assertThat(context.getBeansOfType(SchemaMigrator.class)).isEmpty();
      assertThat(context.getBean(SchemaInitializer.class).status().report().passed()).isTrue();
    }
    assertThat(MigrationSchemas.exists(schema, MigrationSchemas.SAGA_FORGE_HISTORY)).isFalse();
  }
}
