package io.github.gkosharovdev.sagaforge.example.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Process metadata end to end over Testcontainers Postgres ({@code specs/019-process-metadata}
 * T033, SC-001): a correlation id in the MDC of the code that places an order is in the MDC of the
 * saga's steps, their command handlers and the refund compensation, although the start event is
 * stored by one application context and delivered by another, started after the first one stopped.
 *
 * <p>Every command of this example runs in the sending step's or compensation's unit of work, so a
 * {@link PaymentProvider} that records the MDC on each call sees what the step, the handler and the
 * compensation see.
 */
@Tag("container")
class MetadataContainerTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

  /** What the MDC held at each call of the provider. */
  record Seen(String operation, BigDecimal amount, String correlationId) {}

  static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

  /** The example's provider, recording the MDC's correlation id on each call. */
  static final class RecordingPaymentProvider extends PaymentProvider {

    @Override
    public String charge(String idempotencyKey, BigDecimal amount) {
      SEEN.add(new Seen("charge", amount, MDC.get("correlationId")));
      return super.charge(idempotencyKey, amount);
    }

    @Override
    public void refund(String paymentId, BigDecimal amount) {
      SEEN.add(new Seen("refund", amount, MDC.get("correlationId")));
      super.refund(paymentId, amount);
    }
  }

  @BeforeAll
  static void startDatabase() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  private static ConfigurableApplicationContext application(String pollInterval) {
    return new SpringApplicationBuilder(OrderApplication.class)
        .initializers(
            (GenericApplicationContext context) ->
                context.registerBean(
                    "recordingPaymentProvider",
                    RecordingPaymentProvider.class,
                    definition -> definition.setPrimary(true)))
        .run(
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--saga-forge.transport.poll-interval=" + pollInterval);
  }

  private static String column(
      ConfigurableApplicationContext context, String column, String orderId) {
    return context
        .getBean(JdbcTemplate.class)
        .queryForList(
            "SELECT i."
                + column
                + "::text FROM saga_instance i"
                + " JOIN saga_association a ON a.saga_id = i.saga_id WHERE a.association_value = ?",
            String.class,
            orderId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(25);
    }
  }

  private static void place(
      ConfigurableApplicationContext context,
      String correlationId,
      String orderId,
      String amount,
      boolean inStock) {
    MDC.put("correlationId", correlationId);
    try {
      context.getBean(OrderPlacement.class).place(orderId, new BigDecimal(amount), inStock);
      assertThat(MDC.get("correlationId")).isEqualTo(correlationId);
    } finally {
      MDC.remove("correlationId");
    }
  }

  @Test
  @DisplayName("the request's correlation id reaches every step, handler and compensation")
  void correlationIdTravels() throws InterruptedException {
    ConfigurableApplicationContext first = application("1h");
    try {
      place(first, "c-1", "meta-order-1", "12.00", true);
      place(first, "c-2", "meta-order-2", "7.50", false);
      assertThat(column(first, "status", "meta-order-1")).isNull();
    } finally {
      first.close();
    }

    ConfigurableApplicationContext second = application("50ms");
    try {
      await(() -> "COMPLETED".equals(column(second, "status", "meta-order-1")), "order 1");
      await(() -> "COMPENSATED".equals(column(second, "status", "meta-order-2")), "order 2");

      assertThat(SEEN)
          .containsExactlyInAnyOrder(
              new Seen("charge", new BigDecimal("12.00"), "c-1"),
              new Seen("charge", new BigDecimal("7.50"), "c-2"),
              new Seen("refund", new BigDecimal("7.50"), "c-2"));
      assertThat(column(second, "metadata", "meta-order-1"))
          .isEqualTo("{\"correlationId\": \"c-1\"}");
      assertThat(column(second, "metadata", "meta-order-2"))
          .isEqualTo("{\"correlationId\": \"c-2\"}");
      assertThat(
              second
                  .getBean(JdbcTemplate.class)
                  .queryForList(
                      "SELECT DISTINCT h.metadata::text FROM saga_lifecycle_history h"
                          + " JOIN saga_association a ON a.saga_id = h.saga_id"
                          + " WHERE a.association_value = ?",
                      String.class,
                      "meta-order-2"))
          .containsExactly("{\"correlationId\": \"c-2\"}");
      assertThat(MDC.get("correlationId")).isNull();
    } finally {
      second.close();
    }
  }
}
