package io.github.gkosharovdev.sagaforge.spring.support;

import java.util.function.BooleanSupplier;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;

/**
 * Starts a test application over the shared Testcontainers Postgres with emptied tables
 * ({@code specs/008-spring-boot-starter} T018). Spring Modulith's registry is excluded unless a test asks for it.
 */
public final class StarterApp {

    public static final String NO_MODULITH = "spring.autoconfigure.exclude="
            + "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration,"
            + "org.springframework.modulith.events.config.EventPublicationAutoConfiguration,"
            + "org.springframework.modulith.events.config.EventExternalizationAutoConfiguration";

    private StarterApp() {
    }

    /** Empties the tables, then runs {@code app} with {@code initialize-schema=always}, fast polling and no Modulith. */
    public static ConfigurableApplicationContext run(Class<?> app, String... properties) {
        StarterPostgres.reset();
        return restart(app, properties);
    }

    /** Runs {@code app} again over the tables as they are. */
    public static ConfigurableApplicationContext restart(Class<?> app, String... properties) {
        return new SpringApplicationBuilder(app).web(WebApplicationType.NONE)
                .properties("saga-forge.storage.postgres.initialize-schema=always",
                        "saga-forge.transport.poll-interval=50ms", NO_MODULITH)
                .properties(properties)
                .run();
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(StarterPostgres.dataSource());
    }

    /** @return the status of the first instance of {@code sagaType}, or null */
    public static String status(Class<?> sagaType) {
        return jdbc().queryForList("SELECT status FROM saga_instance WHERE saga_type = ?", String.class,
                sagaType.getName()).stream().findFirst().orElse(null);
    }

    public static int count(Class<?> sagaType) {
        return jdbc().queryForObject("SELECT count(*) FROM saga_instance WHERE saga_type = ?", Integer.class,
                sagaType.getName());
    }

    /** Waits up to ten seconds for {@code condition}. */
    public static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; instances "
                        + jdbc().queryForList("SELECT saga_type, status, failure_step_name, failure_cause_type, failure_message FROM saga_instance") + "; messages "
                        + jdbc().queryForList("SELECT payload_type, status, attempts, last_failure::text"
                                + " FROM saga_message_store ORDER BY position"));
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
