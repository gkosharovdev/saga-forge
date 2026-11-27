package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BillingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BookShipment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardDeclined;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.DeliverySaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.LabelSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PackParcel;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PackingRequested;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PackingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ParcelPacked;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RenewalSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RequestPayment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.SubscriptionCancelled;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.SubscriptionStarted;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The User Story 1 to 4 acceptance scenarios, run unchanged in every setup
 * (specs/007-in-memory-transport SC-008, tasks.md T072): plain Java with Saga Forge's own log,
 * Spring with its own log, and Spring with Spring Modulith interop.
 */
public abstract class TransportAcceptanceSuite {

  /** The model every setup registers. */
  public static final Class<?>[] MODEL = {
    FulfilmentSaga.class,
    DeliverySaga.class,
    BillingSaga.class,
    RenewalSaga.class,
    PackingSaga.class,
    LabelSaga.class,
    PaymentService.class,
    ShippingService.class,
    CardService.class,
    RequestPayment.class,
    BookShipment.class,
    ChargeCard.class,
    PackParcel.class
  };

  protected static final Duration WAIT = Duration.ofSeconds(10);

  /** One running setup: engine, transport and log, restartable over the same data. */
  public interface Setup extends AutoCloseable {

    void publish(Object event);

    /**
     * @return whether an instance of {@code sagaType} has {@code status} (a {@code SagaStatus}
     *     name)
     */
    boolean hasInstance(Class<?> sagaType, String status);

    int instanceCount(Class<?> sagaType);

    /** Stops this process (no delivery in flight) and starts a new one over the same data. */
    void restart();

    /**
     * Makes the next delivery of {@code type} fail {@code times} times before the receiver is
     * reached.
     */
    void failDeliveriesOf(Class<?> type, int times);

    MessageStoreQueries queries();

    /**
     * @return whether the setup's outbox can schedule (017); Spring Modulith interop cannot
     */
    default boolean supportsScheduling() {
      return true;
    }

    PaymentService payments();

    CardService cards();

    @Override
    void close();
  }

  /**
   * @return a fresh, started setup with empty data
   */
  protected abstract Setup newSetup();

  protected Setup setup;

  @BeforeEach
  void startSetup() {
    setup = newSetup();
  }

  @AfterEach
  void closeSetup() {
    setup.close();
  }

  protected List<StoredMessage> messagesOf(Class<?> type) {
    return setup.queries().find(MessageQuery.builder().payloadType(type.getName()).build());
  }

  protected void awaitQuiet() {
    await(
        () -> {
          List<StoredMessage> all =
              setup.queries().find(MessageQuery.builder().limit(MessageQuery.MAX_LIMIT).build());
          return !all.isEmpty()
              && all.stream().allMatch(m -> m.status() != MessageStatus.UNCONSUMED);
        },
        WAIT,
        "the store to become quiet");
  }

  @Test
  @DisplayName(
      "US1: an event published outside a unit of work starts a saga; a committed event reaches another")
  void eventsBetweenModules() {
    setup.publish(new OrderPlaced("o-1"));
    await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");

    setup.publish(new PaymentConfirmed("o-1"));

    await(
        () -> setup.hasInstance(DeliverySaga.class, "RUNNING"), WAIT, "the delivery saga to start");
    assertThat(setup.hasInstance(FulfilmentSaga.class, "COMPLETED")).isTrue();
    awaitQuiet();
    assertThat(setup.payments().requested()).containsExactly("o-1");
  }

  @Test
  @DisplayName(
      "US2: a message whose delivery failed before a restart is delivered after it, exactly once")
  void survivesRestart() {
    setup.failDeliveriesOf(OrderPlaced.class, 1);
    setup.publish(new OrderPlaced("o-1"));
    await(
        () -> messagesOf(OrderPlaced.class).stream().anyMatch(m -> m.lastFailure() != null),
        WAIT,
        "the first attempt to fail");

    setup.restart();

    await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
    awaitQuiet();
    assertThat(setup.instanceCount(FulfilmentSaga.class)).isEqualTo(1);
    assertThat(setup.payments().requested()).containsExactly("o-1");
  }

  @Test
  @DisplayName(
      "US3: a outbox command is handled after its step commits, with the identity assigned at send time")
  void outboxCommand() {
    setup.publish(new InvoiceIssued("i-1"));

    await(() -> setup.hasInstance(BillingSaga.class, "COMPLETED"), WAIT, "billing to complete");
    awaitQuiet();
    assertThat(setup.cards().charges())
        .singleElement()
        .satisfies(
            charge -> {
              assertThat(charge.commandId().stepName()).isEqualTo(BillingSaga.START_STEP);
              assertThat(charge.commandId().value())
                  .isEqualTo(messagesOf(ChargeCard.class).get(0).messageId());
            });
  }

  @Test
  @DisplayName(
      "US4: a message that keeps failing is set aside, reported, and delivered again on request")
  void operatorView() {
    setup.failDeliveriesOf(OrderPlaced.class, 1_000);
    setup.publish(new OrderPlaced("o-1"));
    await(
        () ->
            messagesOf(OrderPlaced.class).stream()
                .anyMatch(m -> m.status() == MessageStatus.SET_ASIDE),
        WAIT,
        "the message to be set aside");
    StoredMessage setAside = messagesOf(OrderPlaced.class).get(0);
    assertThat(setAside.lastFailure()).isNotNull();
    assertThat(setAside.setAsideReason()).isEqualTo("MAX_ATTEMPTS");

    setup.failDeliveriesOf(OrderPlaced.class, 0);
    assertThat(setup.queries().redeliver(setAside.messageId())).isTrue();

    await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
    awaitQuiet();
  }

  @Test
  @DisplayName(
      "012: a command the saga sent that fails for good is reported once, compensates, and is listed set aside")
  void failedCommandCompensates() {
    setup.cards().decline();
    setup.publish(new InvoiceIssued("i-1"));

    await(() -> setup.hasInstance(BillingSaga.class, "COMPENSATED"), WAIT, "billing to compensate");
    awaitQuiet();
    assertThat(setup.cards().charges()).hasSize(1);
    assertThat(messagesOf(SagaCompensated.class))
        .singleElement()
        .satisfies(TransportAcceptanceSuite::consumedOutcome);
    assertThat(
            setup.queries().find(MessageQuery.builder().statuses(MessageStatus.SET_ASIDE).build()))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.payloadType()).isEqualTo(ChargeCard.class.getName());
              assertThat(m.setAsideReason()).isEqualTo("FINAL_FAILURE");
              assertThat(m.attempts()).isEqualTo(1);
              assertThat(m.lastFailure().exceptionType()).isEqualTo(CardDeclined.class.getName());
            });
  }

  @Test
  @DisplayName(
      "011: each outcome event is stored once, keyed by its instance, delivered and consumed, never set aside")
  void outcomeEvents() {
    setup.publish(new OrderPlaced(PaymentService.REFUSED_PREFIX + "o-2"));
    setup.publish(new OrderPlaced("o-1"));
    await(
        () -> setup.hasInstance(FulfilmentSaga.class, "FAILED"), WAIT, "the refused saga to fail");
    await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
    setup.publish(new PaymentConfirmed("o-1"));
    await(() -> setup.hasInstance(FulfilmentSaga.class, "COMPLETED"), WAIT, "the saga to complete");
    awaitQuiet();

    assertThat(messagesOf(SagaFailed.class))
        .singleElement()
        .satisfies(TransportAcceptanceSuite::consumedOutcome);
    assertThat(messagesOf(SagaCompleted.class))
        .filteredOn(m -> FulfilmentSaga.class.getName().equals(m.sourceSagaType()))
        .singleElement()
        .satisfies(TransportAcceptanceSuite::consumedOutcome);
  }

  private List<StoredMessage> renewals() {
    return messagesOf(ChargeCard.class).stream()
        .filter(message -> RenewalSaga.class.getName().equals(message.sourceSagaType()))
        .toList();
  }

  private List<CardService.Charge> chargesOf(String subscriptionId) {
    return setup.cards().charges().stream()
        .filter(charge -> charge.invoiceId().equals(subscriptionId))
        .toList();
  }

  @Test
  @DisplayName(
      "017 US4: a scheduled command is held back until due, then handled once through the command receiver")
  void scheduledCommandAfterDue() {
    assumeTrue(setup.supportsScheduling(), "this setup's outbox cannot schedule");
    java.time.Instant published = java.time.Instant.now();
    setup.publish(new SubscriptionStarted("s-1", "PT0.5S"));

    await(() -> !renewals().isEmpty(), WAIT, "the renewal to be stored");
    assertThat(renewals())
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.orderingKey()).isNull();
              assertThat(message.commandId().stepName()).isEqualTo("onStarted");
            });
    await(() -> !chargesOf("s-1").isEmpty(), WAIT, "the renewal charge");
    assertThat(java.time.Duration.between(published, java.time.Instant.now()))
        .isGreaterThanOrEqualTo(java.time.Duration.ofMillis(500));
    await(
        () -> renewals().get(0).status() == MessageStatus.CONSUMED,
        WAIT,
        "the renewal to be consumed");
    assertThat(chargesOf("s-1")).hasSize(1);
  }

  @Test
  @DisplayName("017 US4: a cancelled scheduled command is never handled")
  void cancelledScheduledCommand() throws InterruptedException {
    assumeTrue(setup.supportsScheduling(), "this setup's outbox cannot schedule");
    setup.publish(new SubscriptionStarted("s-2", "PT0.6S"));
    await(() -> setup.hasInstance(RenewalSaga.class, "RUNNING"), WAIT, "the renewal saga");

    setup.publish(new SubscriptionCancelled("s-2"));

    await(() -> setup.hasInstance(RenewalSaga.class, "COMPLETED"), WAIT, "the renewal saga to end");
    assertThat(renewals())
        .singleElement()
        .satisfies(message -> assertThat(message.status()).isEqualTo(MessageStatus.CANCELLED));
    Thread.sleep(900);
    assertThat(chargesOf("s-2")).isEmpty();
  }

  @Test
  @DisplayName("017 US4: a step that rolls back stores nothing it scheduled")
  void rollbackStoresNothing() {
    assumeTrue(setup.supportsScheduling(), "this setup's outbox cannot schedule");
    setup.publish(new SubscriptionStarted(RenewalSaga.BROKEN + "s-3", "PT0S"));

    await(() -> setup.hasInstance(RenewalSaga.class, "FAILED"), WAIT, "the renewal saga to fail");
    assertThat(renewals()).isEmpty();
  }

  @Test
  @DisplayName("017 US4: a command scheduled in the past is due at once")
  void pastIsDueAtOnce() {
    assumeTrue(setup.supportsScheduling(), "this setup's outbox cannot schedule");
    setup.publish(new SubscriptionStarted("s-4", "-PT1H"));

    await(() -> !chargesOf("s-4").isEmpty(), WAIT, "the renewal charge");
  }

  @Test
  @DisplayName(
      "017 US4 + 012: a scheduled command that fails for good reaches the sender's failure feedback")
  void scheduledCommandFailureFeedback() {
    assumeTrue(setup.supportsScheduling(), "this setup's outbox cannot schedule");
    setup.cards().decline();
    setup.publish(new SubscriptionStarted("s-5", "PT0S"));

    await(
        () -> setup.hasInstance(RenewalSaga.class, "COMPENSATED"),
        WAIT,
        "the renewal saga to compensate");
    assertThat(renewals())
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.status()).isEqualTo(MessageStatus.SET_ASIDE);
              assertThat(message.setAsideReason()).isEqualTo("FINAL_FAILURE");
            });
  }

  @Test
  @DisplayName(
      "014 Property 47: an event its own saga ran in process also starts another saga type, once")
  void recordedEventFansOut() {
    setup.failDeliveriesOf(ParcelPacked.class, 1);

    setup.publish(new PackingRequested("p-1"));

    await(() -> setup.hasInstance(LabelSaga.class, "RUNNING"), WAIT, "the label saga to start");
    awaitQuiet();
    assertThat(setup.hasInstance(PackingSaga.class, "COMPLETED")).isTrue();
    assertThat(setup.instanceCount(PackingSaga.class)).isEqualTo(1);
    assertThat(setup.instanceCount(LabelSaga.class)).isEqualTo(1);
    assertThat(messagesOf(ParcelPacked.class))
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.status()).isEqualTo(MessageStatus.CONSUMED);
              assertThat(message.lastFailure()).isNotNull();
              assertThat(message.sourceSagaType()).isEqualTo(PackingSaga.class.getName());
            });
  }

  private static void consumedOutcome(StoredMessage message) {
    assertThat(message.status()).isEqualTo(MessageStatus.CONSUMED);
    assertThat(message.attempts()).isEqualTo(1);
    assertThat(message.sourceSagaId()).isNotNull();
    assertThat(message.orderingKey()).isEqualTo("saga:" + message.sourceSagaId());
  }
}
