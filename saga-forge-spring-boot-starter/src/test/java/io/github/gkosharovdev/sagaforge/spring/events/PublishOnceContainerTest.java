package io.github.gkosharovdev.sagaforge.spring.events;

import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.await;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.jdbc;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.status;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;
import io.github.gkosharovdev.sagaforge.spring.modulith.ModulithMessageStore;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.ModulithApp;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.notifications.PaymentNotifier;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.orders.OrderPlaced;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.orders.PaymentSaga;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User Story 1 over Postgres and Spring Modulith's registry with Saga Forge's own log
 * (specs/014-publish-once): an event published through Spring's {@code ApplicationEventPublisher}
 * in a real Spring transaction is stored with it and reaches the saga after commit, and nothing on
 * rollback. The routes without a database are in {@link PublishOnceTest}.
 */
@Tag("container")
class PublishOnceContainerTest {

  private ConfigurableApplicationContext context;

  @BeforeEach
  void start() {
    StarterPostgres.reset();
    context =
        new SpringApplicationBuilder(ModulithApp.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.modulith.events.jdbc.schema-initialization.enabled=true",
                "saga-forge.transport.modulith-interop=false",
                "saga-forge.transport.poll-interval=50ms")
            .run();
  }

  @AfterEach
  void stop() {
    context.close();
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  private int storedOrderPlaced() {
    return jdbc()
        .queryForObject(
            "SELECT count(*) FROM saga_message_store WHERE payload_type = ?",
            Integer.class,
            OrderPlaced.class.getName());
  }

  @Test
  @DisplayName("two logs: Saga Forge's own message store beside Modulith's registry")
  void twoLogs() {
    assertThat(context.getBean(MessageStore.class)).isNotInstanceOf(ModulithMessageStore.class);
  }

  @Test
  @DisplayName("published through Spring in a committed transaction: stored once, the saga runs")
  void committed() {
    transaction()
        .executeWithoutResult(
            status -> context.publishEvent(new OrderPlaced("s-1", new BigDecimal("5"))));

    await(() -> "COMPLETED".equals(status(PaymentSaga.class)), "the saga to complete");
    await(
        () -> context.getBean(PaymentNotifier.class).notified().contains("s-1"),
        "the module listener");
    assertThat(storedOrderPlaced()).isEqualTo(1);
    assertThat(context.getBean(PaymentNotifier.class).notified()).containsExactly("s-1");
  }

  @Test
  @DisplayName("published through Spring in a rolled-back transaction: nothing stored, no saga")
  void rolledBack() throws InterruptedException {
    transaction()
        .executeWithoutResult(
            status -> {
              context.publishEvent(new OrderPlaced("s-2", new BigDecimal("5")));
              status.setRollbackOnly();
            });

    Thread.sleep(300);
    assertThat(storedOrderPlaced()).isZero();
    assertThat(status(PaymentSaga.class)).isNull();
  }

  @Test
  @DisplayName("published through Spring with no transaction: stored at once, the saga runs")
  void noTransaction() {
    context.publishEvent(new OrderPlaced("s-3", new BigDecimal("5")));

    await(() -> "COMPLETED".equals(status(PaymentSaga.class)), "the saga to complete");
    assertThat(storedOrderPlaced()).isEqualTo(1);
  }
}
