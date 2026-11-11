package io.github.gkosharovdev.sagaforge.spring.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties.SchemaInitialization;

/**
 * Creates Saga Forge's Postgres tables from the module's {@code schema.sql} when they do not exist yet
 * ({@code saga-forge.storage.postgres.initialize-schema=always}), or fails startup naming the missing tables
 * ({@code never}, the default). Existing tables are never altered; upgrades stay the application's job (G-003-25).
 */
public class SchemaInitializer {

    /** The schema script on the class path, from {@code saga-forge-storage-postgres}. */
    public static final String SCHEMA = "io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql";

    static final List<String> TABLES = List.of("saga_instance", "saga_association", "saga_context",
            "saga_lifecycle_history", "saga_message_store", "saga_message_store_consumer");

    private final DataSource dataSource;
    private final SchemaInitialization policy;

    public SchemaInitializer(DataSource dataSource, SchemaInitialization policy) {
        this.dataSource = dataSource;
        this.policy = policy;
    }

    /**
     * Applies the policy.
     *
     * @throws SagaForgeConfigurationException with {@code never}, if a table is missing; with {@code always}, if only
     *                                         some tables exist
     */
    public void initialize() {
        List<String> missing = missingTables();
        if (missing.isEmpty()) {
            return;
        }
        if (policy == SchemaInitialization.ALWAYS && missing.size() == TABLES.size()) {
            new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource);
            return;
        }
        if (policy == SchemaInitialization.ALWAYS) {
            throw new SagaForgeConfigurationException("Saga Forge's schema is incomplete: tables " + missing
                    + " are missing while others exist.",
                    "Apply the matching schema-upgrade-NNN.sql scripts of saga-forge-storage-postgres; the starter only"
                            + " creates the schema in an empty database.");
        }
        throw new SagaForgeConfigurationException("Saga Forge's tables " + missing + " do not exist in the database.",
                "Apply classpath:" + SCHEMA + " (and its upgrade scripts) with Flyway, Liquibase or by hand, or set "
                        + "saga-forge.storage.postgres.initialize-schema=always to create them at startup.");
    }

    private List<String> missingTables() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            List<String> missing = new ArrayList<>();
            for (String table : TABLES) {
                if (!exists(meta, connection.getSchema(), table)) {
                    missing.add(table);
                }
            }
            return missing;
        } catch (SQLException e) {
            throw new SagaForgeConfigurationException("Saga Forge could not inspect the database schema: "
                    + e.getMessage(), "Check the DataSource configuration (spring.datasource.*).");
        }
    }

    private static boolean exists(DatabaseMetaData meta, String schema, String table) throws SQLException {
        try (ResultSet tables = meta.getTables(null, schema, table.toLowerCase(Locale.ROOT), new String[] { "TABLE" })) {
            return tables.next();
        }
    }
}
