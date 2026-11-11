package io.github.gkosharovdev.sagaforge.spring;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One {@code postgres:16-alpine} container per test JVM with Saga Forge's {@code schema.sql}; {@link #reset()} empties
 * every table, Spring Modulith's included. Classes using it carry {@code @Tag("container")}.
 */
public final class StarterPostgres {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop, "starter-postgres-stop"));
        execute(schema());
    }

    private StarterPostgres() {
    }

    public static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static void reset() {
        execute("TRUNCATE saga_instance CASCADE;"
                + "TRUNCATE saga_message_store, saga_message_store_consumer RESTART IDENTITY;"
                + "DO $$ BEGIN IF to_regclass('event_publication') IS NOT NULL THEN"
                + " EXECUTE 'TRUNCATE event_publication'; END IF; END $$;"
                + "DO $$ BEGIN IF to_regclass('event_publication_archive') IS NOT NULL THEN"
                + " EXECUTE 'TRUNCATE event_publication_archive'; END IF; END $$");
    }

    private static String schema() {
        try (InputStream in = StarterPostgres.class.getResourceAsStream(
                "/io/github/gkosharovdev/sagaforge/storage/postgres/schema.sql")) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return script.lines().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String script) {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : splitStatements(script)) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not run " + script, e);
        }
    }

    private static Iterable<String> splitStatements(String script) {
        if (script.contains("$$")) {
            // reset(): statements are separated by ';' outside the DO blocks, which contain none of their own.
            return Arrays.stream(script.split(";(?=TRUNCATE|DO )")).map(String::strip).filter(s -> !s.isEmpty())
                    .toList();
        }
        return Arrays.stream(script.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
