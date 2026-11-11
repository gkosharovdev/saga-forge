package io.github.gkosharovdev.sagaforge.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.SchemaInitialization;
import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;

/** Schema initialization ({@code specs/008-spring-boot-starter} FR-016, tasks.md T036), each test in a fresh schema. */
@Tag("container")
class SchemaInitializerContainerTest {

    private PGSimpleDataSource dataSource;
    private String schema;

    @BeforeEach
    void emptySchema() {
        schema = "s_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(StarterPostgres.dataSource()).execute("CREATE SCHEMA " + schema);
        dataSource = (PGSimpleDataSource) StarterPostgres.dataSource();
        dataSource.setCurrentSchema(schema);
    }

    private List<String> tables() {
        return new JdbcTemplate(dataSource).queryForList("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = ? ORDER BY table_name", String.class, schema);
    }

    @Test
    @DisplayName("always, on an empty schema: creates every table")
    void alwaysCreates() {
        new SchemaInitializer(dataSource, SchemaInitialization.ALWAYS).initialize();
        assertThat(tables()).containsAll(SchemaInitializer.TABLES);
    }

    @Test
    @DisplayName("never, on an empty schema: fails naming the tables and the script")
    void neverFails() {
        assertThatThrownBy(() -> new SchemaInitializer(dataSource, SchemaInitialization.NEVER).initialize())
                .isInstanceOf(SagaForgeConfigurationException.class).hasMessageContaining("saga_instance")
                .satisfies(e -> assertThat(((SagaForgeConfigurationException) e).action())
                        .contains(SchemaInitializer.SCHEMA).contains("initialize-schema=always"));
    }

    @Test
    @DisplayName("existing tables are never altered")
    void existingUntouched() {
        new SchemaInitializer(dataSource, SchemaInitialization.ALWAYS).initialize();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE saga_message_store_consumer ADD COLUMN marker TEXT DEFAULT 'kept'");

        new SchemaInitializer(dataSource, SchemaInitialization.ALWAYS).initialize();
        new SchemaInitializer(dataSource, SchemaInitialization.NEVER).initialize();

        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'saga_message_store_consumer'", String.class, schema)).contains("marker");
    }

    @Test
    @DisplayName("always, on a partial schema: fails naming the missing tables")
    void partialFails() {
        new SchemaInitializer(dataSource, SchemaInitialization.ALWAYS).initialize();
        new JdbcTemplate(dataSource).execute("DROP TABLE saga_message_store_consumer");
        assertThatThrownBy(() -> new SchemaInitializer(dataSource, SchemaInitialization.ALWAYS).initialize())
                .isInstanceOf(SagaForgeConfigurationException.class)
                .hasMessageContaining("saga_message_store_consumer");
    }
}
