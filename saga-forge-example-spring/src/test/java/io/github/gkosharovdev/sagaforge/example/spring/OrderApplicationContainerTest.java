package io.github.gkosharovdev.sagaforge.example.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Spring example end to end over Testcontainers Postgres ({@code specs/008-spring-boot-starter} quickstart
 * scenario 1, SC-001, SC-002): an order completes; a stock shortage after payment ends {@code COMPENSATED} with the
 * payment refunded.
 */
@Tag("container")
class OrderApplicationContainerTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        context = SpringApplication.run(OrderApplication.class, "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--saga-forge.transport.poll-interval=50ms");
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    private static String status(String orderId) {
        return context.getBean(JdbcTemplate.class).queryForList("SELECT i.status FROM saga_instance i"
                + " JOIN saga_association a ON a.saga_id = i.saga_id WHERE a.association_value = ?", String.class,
                orderId).stream().findFirst().orElse(null);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    @Test
    @DisplayName("an order in stock is paid for and completed")
    void completes() throws InterruptedException {
        context.getBean(OrderPlacement.class).place("order-1", new BigDecimal("42.00"), true);

        await(() -> "COMPLETED".equals(status("order-1")), "the saga to complete");
        assertThat(context.getBean(OrderRepository.class).findById("order-1").orElseThrow().status())
                .isEqualTo(Order.Status.COMPLETED);
        assertThat(context.getBean(PaymentProvider.class).charges()).containsValue(new BigDecimal("42.00"));
    }

    @Test
    @DisplayName("a stock shortage after payment is compensated: the payment is refunded")
    void compensates() throws InterruptedException {
        context.getBean(OrderPlacement.class).place("order-2", new BigDecimal("9.99"), false);

        await(() -> "COMPENSATED".equals(status("order-2")), "the saga to compensate");
        assertThat(context.getBean(OrderRepository.class).findById("order-2").orElseThrow().status())
                .isEqualTo(Order.Status.OUT_OF_STOCK);
        assertThat(context.getBean(PaymentProvider.class).refunds()).containsValue(new BigDecimal("9.99"));
    }
}
