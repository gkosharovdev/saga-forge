package io.github.gkosharovdev.sagaforge.spring.storage;

import static io.github.gkosharovdev.sagaforge.spring.storage.MigrationSchemas.SAGA_FORGE_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchemaValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Three applications start with {@code initialize-schema=migrate} against one database at once
 * ({@code specs/018-versioned-schema-migrations} T025, T031; FR-010, SC-005): Flyway's lock lets
 * one apply each version, the others find it applied, and all three start.
 */
@Tag("container")
class ConcurrentMigrationTest {

  private static final int APPLICATIONS = 3;
  private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";

  private static String loggingSystem;

  /**
   * Spring Boot initialises its logging system on every start, and concurrent starts race on it
   * ("Unable to find Spring Environment in logger context"); the logging set up before stays.
   */
  @BeforeAll
  static void noLoggingSystem() {
    loggingSystem = System.setProperty(LOGGING_SYSTEM, "none");
  }

  @AfterAll
  static void restoreLoggingSystem() {
    if (loggingSystem == null) {
      System.clearProperty(LOGGING_SYSTEM);
    } else {
      System.setProperty(LOGGING_SYSTEM, loggingSystem);
    }
  }

  private static List<ConfigurableApplicationContext> startTogether(String schema)
      throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(APPLICATIONS);
    try {
      List<Future<ConfigurableApplicationContext>> starts = new ArrayList<>();
      for (int i = 0; i < APPLICATIONS; i++) {
        starts.add(
            pool.submit(
                () -> {
                  go.await();
                  return FlywaySchemaMigrationIntegrationTest.start(
                      schema, "saga-forge.recovery.enabled=false");
                }));
      }
      go.countDown();
      List<ConfigurableApplicationContext> contexts = new ArrayList<>();
      for (Future<ConfigurableApplicationContext> start : starts) {
        contexts.add(start.get(60, TimeUnit.SECONDS));
      }
      return contexts;
    } finally {
      pool.shutdownNow();
    }
  }

  private static void closeAll(List<ConfigurableApplicationContext> contexts) {
    contexts.forEach(ConfigurableApplicationContext::close);
  }

  @Test
  @DisplayName("an empty database: each version applied exactly once, all three start")
  void emptyDatabase() throws Exception {
    String schema = MigrationSchemas.create();

    List<ConfigurableApplicationContext> contexts = startTogether(schema);
    try {
      assertThat(contexts).hasSize(APPLICATIONS).allMatch(ConfigurableApplicationContext::isActive);
      assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
          .containsExactly("1", "2", "3", "4", "5", "6");
      assertThat(
              new PostgresSchemaValidator(MigrationSchemas.dataSource(schema)).validate().passed())
          .isTrue();
    } finally {
      closeAll(contexts);
    }
  }

  @Test
  @DisplayName("a released 0.1.0 database with rows: one baseline row, every row kept")
  void releasedDatabase() throws Exception {
    String schema = MigrationSchemas.create();
    MigrationSchemas.run(schema, "/takeover/schema-0.1.0.sql");
    MigrationSchemas.run(schema, "/takeover/rows-common.sql");
    MigrationSchemas.run(schema, "/takeover/rows-0.1.0.sql");

    List<ConfigurableApplicationContext> contexts = startTogether(schema);
    try {
      assertThat(contexts).hasSize(APPLICATIONS).allMatch(ConfigurableApplicationContext::isActive);
      assertThat(MigrationSchemas.types(schema, SAGA_FORGE_HISTORY))
          .containsExactly("BASELINE", "SQL", "SQL", "SQL", "SQL", "SQL");
      assertThat(MigrationSchemas.versions(schema, SAGA_FORGE_HISTORY))
          .containsExactly("1", "2", "3", "4", "5", "6");
      assertThat(MigrationSchemas.count(schema, "saga_instance")).isEqualTo(2);
      assertThat(MigrationSchemas.count(schema, "saga_association")).isEqualTo(2);
      assertThat(MigrationSchemas.count(schema, "saga_context")).isEqualTo(1);
      assertThat(MigrationSchemas.count(schema, "saga_lifecycle_history")).isEqualTo(2);
      assertThat(MigrationSchemas.count(schema, "saga_message_store")).isEqualTo(1);
    } finally {
      closeAll(contexts);
    }
  }
}
