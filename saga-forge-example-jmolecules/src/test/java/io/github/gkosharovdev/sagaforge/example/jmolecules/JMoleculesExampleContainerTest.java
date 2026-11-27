package io.github.gkosharovdev.sagaforge.example.jmolecules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.example.jmolecules.application.JMoleculesOrderApplication;
import io.github.gkosharovdev.sagaforge.example.jmolecules.application.OrderPlacement;
import io.github.gkosharovdev.sagaforge.example.jmolecules.application.OrderRepository;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Order;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderId;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Payments;
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
 * The jMolecules example end to end over Testcontainers Postgres (specs/016-marker-spi-jmolecules
 * US1, quickstart §6): completion, a stock shortage compensated, a declined payment failed.
 */
@Tag("container")
class JMoleculesExampleContainerTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
  private static ConfigurableApplicationContext context;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    context =
        SpringApplication.run(
            JMoleculesOrderApplication.class,
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
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
    return context
        .getBean(JdbcTemplate.class)
        .queryForList(
            "SELECT i.status FROM saga_instance i"
                + " JOIN saga_association a ON a.saga_id = i.saga_id WHERE a.association_value = ?",
            String.class,
            orderId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + 15_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what + ", status " + status(what));
      }
      Thread.sleep(25);
    }
  }

  private static Order order(String id) {
    return context.getBean(OrderRepository.class).findById(new OrderId(id)).orElseThrow();
  }

  @Test
  @DisplayName("an order in stock is paid for and completed")
  void completes() throws InterruptedException {
    context.getBean(OrderPlacement.class).place("jm-1", new BigDecimal("42.00"), true);

    await(() -> "COMPLETED".equals(status("jm-1")), "jm-1");
    assertThat(order("jm-1").status()).isEqualTo(Order.Status.COMPLETED);
    assertThat(context.getBean(Payments.class).charges())
        .containsEntry("pay-jm-1", new BigDecimal("42.00"));
  }

  @Test
  @DisplayName("a stock shortage after payment is compensated: the payment is refunded")
  void compensates() throws InterruptedException {
    context.getBean(OrderPlacement.class).place("jm-2", new BigDecimal("9.99"), false);

    await(() -> "COMPENSATED".equals(status("jm-2")), "jm-2");
    assertThat(order("jm-2").status()).isEqualTo(Order.Status.OUT_OF_STOCK);
    assertThat(context.getBean(Payments.class).refunds()).containsValue(new BigDecimal("9.99"));
  }

  @Test
  @DisplayName("a declined payment fails the saga")
  void fails() throws InterruptedException {
    context.getBean(OrderPlacement.class).place("jm-3", new BigDecimal("-1.00"), true);

    await(() -> "FAILED".equals(status("jm-3")), "jm-3");
    assertThat(order("jm-3").status()).isEqualTo(Order.Status.PLACED);
  }
}
