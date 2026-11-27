package io.github.gkosharovdev.sagaforge.spring.storage;

import static io.github.gkosharovdev.sagaforge.spring.storage.MigrationSchemas.APP_HISTORY;
import static io.github.gkosharovdev.sagaforge.spring.storage.MigrationSchemas.SAGA_FORGE_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.MigrationApp;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.Order;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderReadyToShipEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.StockUnavailableEvent;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderProcessingSaga;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderRepository;
import io.github.gkosharovdev.sagaforge.spring.support.StarterApp;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code initialize-schema=migrate} in a Spring Boot application ({@code
 * specs/018-versioned-schema-migrations} T024, T029; SC-001, SC-002): beside the application's own
 * Flyway migrations, on its own, and taking over databases built from released {@code schema.sql}
 * files. Each test runs in its own schema.
 */
@Tag("container")
class FlywaySchemaMigrationIntegrationTest {

  private static final List<String> SAGA_FORGE_VERSIONS = List.of("1", "2", "3", "4", "5");

  @TempDir Path appMigrations;

  static ConfigurableApplicationContext start(String schema, String... properties) {
    // Later properties replace earlier ones with the same key.
    List<String> all = new ArrayList<>();
    all.add("test.schema=" + schema);
    all.add("saga-forge.storage.postgres.initialize-schema=migrate");
    all.add("saga-forge.transport.poll-interval=50ms");
    all.add(StarterApp.NO_MODULITH);
    all.addAll(List.of(properties));
    // Command-line arguments: they outrank the test application.properties, which disables Spring
    // Boot's Flyway for every other test.
    return new SpringApplicationBuilder(MigrationApp.class)
        .web(WebApplicationType.NONE)
        .run(
            all.stream()
                .collect(
                    Collectors.toMap(
                        property -> property.substring(0, property.indexOf('=')),
                        property -> "--" + property,
                        (earlier, later) -> later,
                        LinkedHashMap::new))
                .values()
                .toArray(String[]::new));
  }

  private static List<String> numbers(int from, int to) {
    return IntStream.rangeClosed(from, to).mapToObj(String::valueOf).toList();
  }

  private static String status(String schema, Class<?> sagaType) {
    return MigrationSchemas.jdbc(schema)
        .queryForList(
            "SELECT status FROM saga_instance WHERE saga_type = ?",
            String.class,
            sagaType.getName())
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static void awaitStatus(String schema, Class<?> sagaType, String expected) {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (!expected.equals(status(schema, sagaType))) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            sagaType.getSimpleName() + " is " + status(schema, sagaType) + ", not " + expected);
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  @Test
  @DisplayName("beside the application's own V1..V62: both histories complete and a saga completes")
  void besideApplicationMigrations() {
    String schema = MigrationSchemas.create();
    String location =
        MigrationSchemas.applicationMigrations(
            appMigrations,
            62,
            // Records whether Saga Forge's history existed when the application's V1 ran.
            Map.of(
                1,
                "CREATE TABLE app_t1 (saga_forge_first BOOLEAN);\n"
                    + "INSERT INTO app_t1 SELECT to_regclass('"
                    + SAGA_FORGE_HISTORY
                    + "') IS NOT NULL;\n"));

    try (ConfigurableApplicationContext context =
        start(schema, "spring.flyway.enabled=true", "spring.flyway.locations=" + location)) {
      assertThat(MigrationSchemas.versions(schema, APP_HISTORY)).isEqualTo(numbers(1, 62));
      // The application's tables make the schema non-empty: Saga Forge's history starts with a
      // baseline at 0, then applies every version.
      assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
          .containsExactly("0", "1", "2", "3", "4", "5");
      assertThat(
              MigrationSchemas.jdbc(schema)
                  .queryForObject("SELECT saga_forge_first FROM app_t1", Boolean.class))
          .isFalse();

      context.getBean(OrderRepository.class).save(new Order("o-1", true));
      context
          .getBean(EventPublisher.class)
          .publish(new OrderCreatedEvent("o-1", new BigDecimal("12.50")));
      awaitStatus(schema, OrderProcessingSaga.class, "COMPLETED");
    }

    try (ConfigurableApplicationContext restarted =
        start(schema, "spring.flyway.enabled=true", "spring.flyway.locations=" + location)) {
      assertThat(MigrationSchemas.count(schema, APP_HISTORY)).isEqualTo(62);
      assertThat(MigrationSchemas.count(schema, SAGA_FORGE_HISTORY)).isEqualTo(6);
    }
  }

  @Test
  @DisplayName("with spring.flyway.enabled=false: Saga Forge still migrates")
  void withoutApplicationFlyway() {
    String schema = MigrationSchemas.create();

    try (ConfigurableApplicationContext context = start(schema, "spring.flyway.enabled=false")) {
      assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
          .isEqualTo(SAGA_FORGE_VERSIONS);
      assertThat(MigrationSchemas.exists(schema, APP_HISTORY)).isFalse();
      assertThat(context.getBean(SchemaInitializer.class).status().version())
          .isEqualTo(PostgresSchema.currentVersion());
    }
  }

  @ParameterizedTest(name = "release {0}")
  @CsvSource({"0.1.0, 1, 2", "0.2.0, 2, 3", "0.4.0, 4, 3"})
  @DisplayName("a released database with rows: baselined at its version, every row kept")
  void takeover(String release, int baseline, int historyRowsAfter) {
    String schema = MigrationSchemas.create();
    MigrationSchemas.run(schema, "/takeover/schema-" + release + ".sql");
    MigrationSchemas.run(schema, "/takeover/rows-common.sql");
    MigrationSchemas.run(schema, "/takeover/rows-" + release + ".sql");
    int messages = MigrationSchemas.count(schema, "saga_message_store");

    try (ConfigurableApplicationContext context =
        start(schema, "saga-forge.recovery.enabled=false")) {
      List<String> expected = numbers(baseline, 5);
      assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY)).isEqualTo(expected);
      assertThat(MigrationSchemas.types(schema, SAGA_FORGE_HISTORY).getFirst())
          .isEqualTo("BASELINE");

      assertThat(MigrationSchemas.count(schema, "saga_instance")).isEqualTo(2);
      assertThat(MigrationSchemas.count(schema, "saga_association")).isEqualTo(2);
      assertThat(MigrationSchemas.count(schema, "saga_context")).isEqualTo(1);
      assertThat(MigrationSchemas.count(schema, "saga_message_store")).isEqualTo(messages);
      // V2 deletes the REQUIRES_INTERVENTION lifecycle entry of a 0.1.0 database, nothing else.
      assertThat(MigrationSchemas.count(schema, "saga_lifecycle_history"))
          .isEqualTo(historyRowsAfter);
      assertThat(
              MigrationSchemas.jdbc(schema)
                  .queryForObject(
                      "SELECT status FROM saga_instance WHERE saga_id = 'stuck-1'", String.class))
          .isEqualTo("COMPENSATION_FAILED");
      // V4 drops the consumer heartbeats of 0.1.0 and 0.2.0.
      assertThat(MigrationSchemas.exists(schema, "saga_message_store_consumer")).isFalse();
      assertThat(context.getBean(SchemaInitializer.class).status().report().passed()).isTrue();
    }
  }

  @Test
  @DisplayName("a saga started before the switch to migrate completes after it")
  void sagaAcrossTheSwitch() {
    String schema = MigrationSchemas.create();
    try (ConfigurableApplicationContext before =
        start(schema, "saga-forge.storage.postgres.initialize-schema=always")) {
      before.getBean(EventPublisher.class).publish(new OrderReadyToShipEvent("o-9"));
      awaitStatus(schema, FulfilmentSaga.class, "RUNNING");
    }
    assertThat(MigrationSchemas.exists(schema, SAGA_FORGE_HISTORY)).isFalse();

    try (ConfigurableApplicationContext after = start(schema)) {
      assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY)).containsExactly("5");
      after.getBean(EventPublisher.class).publish(new StockUnavailableEvent("o-9"));
      awaitStatus(schema, FulfilmentSaga.class, "COMPLETED");
    }
  }

  @Test
  @DisplayName(
      "an application whose own V62 applied schema-0.1.0.sql: its history unchanged, V1 baseline")
  void applicationCopiedTheSchema() {
    String schema = MigrationSchemas.create();
    String location =
        MigrationSchemas.applicationMigrations(
            appMigrations, 62, Map.of(62, MigrationSchemas.read("/takeover/schema-0.1.0.sql")));

    try (ConfigurableApplicationContext context =
        start(schema, "spring.flyway.enabled=true", "spring.flyway.locations=" + location)) {
      assertThat(MigrationSchemas.versions(schema, APP_HISTORY)).isEqualTo(numbers(1, 62));
      assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
          .isEqualTo(SAGA_FORGE_VERSIONS);
      assertThat(MigrationSchemas.types(schema, SAGA_FORGE_HISTORY).getFirst())
          .isEqualTo("BASELINE");
    }
  }
}
