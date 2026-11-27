package io.github.gkosharovdev.sagaforge.spring.storage;

import static io.github.gkosharovdev.sagaforge.spring.storage.MigrationSchemas.SAGA_FORGE_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.spring.diagnostics.SagaSchemaFailureAnalyzer;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code initialize-schema=validate} in a Spring Boot application ({@code
 * specs/018-versioned-schema-migrations} T034; FR-014, FR-015): it never writes, fails on a missing
 * or different table, column or constraint with every difference listed, and warns on a missing
 * index. {@code never} reports the same differences as warnings.
 */
@Tag("container")
class SchemaValidateModeTest {

  private static final String VALIDATE = "saga-forge.storage.postgres.initialize-schema=validate";

  private static String currentSchema() {
    String schema = MigrationSchemas.create();
    MigrationSchemas.run(schema, "/" + PostgresSchema.SCHEMA_SCRIPT);
    return schema;
  }

  private static FailureAnalysis failedStart(String schema) {
    Throwable failure =
        catchThrowable(() -> FlywaySchemaMigrationIntegrationTest.start(schema, VALIDATE).close());
    assertThat(failure).isNotNull();
    FailureAnalysis analysis = new SagaSchemaFailureAnalyzer().analyze(failure);
    assertThat(analysis).isNotNull();
    return analysis;
  }

  @Test
  @DisplayName("a current schema: starts, and no history table is created")
  void currentSchemaStarts() {
    String schema = currentSchema();

    try (ConfigurableApplicationContext context =
        FlywaySchemaMigrationIntegrationTest.start(schema, VALIDATE)) {
      assertThat(context.getBean(SchemaInitializer.class).status().report().passed()).isTrue();
    }
    assertThat(MigrationSchemas.exists(schema, SAGA_FORGE_HISTORY)).isFalse();
  }

  @Test
  @DisplayName("a dropped column: fails naming the table, the column and the expected type")
  void droppedColumn() {
    String schema = currentSchema();
    MigrationSchemas.jdbc(schema).execute("ALTER TABLE saga_context DROP COLUMN last_modified_at");

    FailureAnalysis analysis = failedStart(schema);

    assertThat(analysis.getDescription())
        .contains(
            "saga_context: column last_modified_at is missing (expected timestamp with time zone"
                + " NOT NULL)");
    assertThat(analysis.getAction()).contains("initialize-schema=migrate");
  }

  @Test
  @DisplayName("a wrong type, a dropped check and an extra column: lists the first two only")
  void severalDifferences() {
    String schema = currentSchema();
    MigrationSchemas.jdbc(schema)
        .execute("ALTER TABLE saga_message_store ALTER COLUMN attempts TYPE BIGINT");
    MigrationSchemas.jdbc(schema)
        .execute("ALTER TABLE saga_message_store DROP CONSTRAINT lease_together");
    MigrationSchemas.jdbc(schema).execute("ALTER TABLE saga_instance ADD COLUMN extra TEXT");

    String description = failedStart(schema).getDescription();

    assertThat(description)
        .contains("2 failures")
        .contains("column attempts is different (expected integer NOT NULL, found bigint NOT NULL)")
        .contains("leased_by")
        .doesNotContain("extra");
  }

  @Test
  @DisplayName("a dropped index: starts and warns naming the index")
  void droppedIndex() {
    String schema = currentSchema();
    MigrationSchemas.jdbc(schema).execute("DROP INDEX saga_message_store_due");

    try (ConfigurableApplicationContext context =
        FlywaySchemaMigrationIntegrationTest.start(schema, VALIDATE)) {
      SchemaStatus status = context.getBean(SchemaInitializer.class).status();
      assertThat(status.report().passed()).isTrue();
      assertThat(status.report().warnings()).hasSize(1);
      assertThat(status.report().describe())
          .contains("WARNING saga_message_store: index")
          .contains("next_attempt_at");
    }
  }

  @Test
  @DisplayName("never, with a dropped column: starts and warns")
  void neverWarns() {
    String schema = currentSchema();
    MigrationSchemas.jdbc(schema).execute("ALTER TABLE saga_context DROP COLUMN last_modified_at");

    try (ConfigurableApplicationContext context =
        FlywaySchemaMigrationIntegrationTest.start(
            schema, "saga-forge.storage.postgres.initialize-schema=never")) {
      assertThat(context.isActive()).isTrue();
      assertThat(context.getBean(SchemaInitializer.class).status().report().describe())
          .contains("saga_context: column last_modified_at is missing");
    }
  }
}
