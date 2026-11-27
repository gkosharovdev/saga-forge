package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.DuplicateMessageException;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderElection;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeadershipLostException;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaseLostException;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link MessageStore} contract ({@code specs/007-in-memory-transport} contracts/core-spi.md,
 * data-model.md), run against every implementation: {@code InMemoryMessageStoreTest} here and the
 * Postgres container suite.
 *
 * <p>Payloads are {@link Paid} records, which every implementation must round-trip. Subclasses
 * supply a fresh log per test, the boundary its transactions join, and a way to make a message
 * unreadable.
 */
public abstract class MessageStoreContract {

  /** A payload every implementation can encode. */
  public record Paid(String orderId, long amount) {}

  protected static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");
  protected static final Duration LEASE = Duration.ofMinutes(5);

  /** How long a leadership taken by {@link #lease(String, Instant, int)} lasts. */
  protected static final Duration LEADERSHIP = Duration.ofSeconds(30);

  /**
   * @return the store under test, empty
   */
  protected abstract MessageStore log();

  /**
   * @return the boundary whose transactions {@link #log()} joins
   */
  protected abstract TransactionBoundary transactions();

  /** Makes the payload of an appended message undecodable. */
  protected abstract void makeUnreadable(String messageId);

  protected void appendCommitted(NewMessage... entries) {
    inTransaction(tx -> log().append(tx, List.of(entries)));
  }

  protected void inTransaction(Consumer<LocalTransaction> work) {
    LocalTransaction tx = transactions().begin();
    try {
      work.accept(tx);
      tx.commit();
    } catch (RuntimeException e) {
      tx.rollback();
      throw e;
    }
  }

  protected static NewMessage event(String id, String key) {
    return new NewMessage(
        id,
        MessageKind.EVENT,
        Paid.class.getName(),
        new Paid("o-" + id, 10),
        null,
        key,
        "Saga",
        "s-1",
        T0);
  }

  protected static NewMessage command(String sagaId) {
    CommandId commandId = CommandId.of(sagaId, "step", Paid.class.getName(), 1);
    return new NewMessage(
        commandId.value(),
        MessageKind.COMMAND,
        Paid.class.getName(),
        new Paid("o-c", 5),
        commandId,
        "saga:" + sagaId,
        null,
        null,
        T0);
  }

  /**
   * Leases as {@code consumer}: takes the store's leadership for it first (013), releasing another
   * consumer's, so the 007 and 017 cases below keep naming consumers as before.
   */
  protected LeasedMessages lease(String consumer, Instant now, int limit) {
    return log().lease(leaderFor(consumer, now), now, LEASE, limit);
  }

  private LeaderLease leaderFor(String consumer, Instant now) {
    LeaderElection election = log().leaderElection();
    Optional<LeaderLease> current = election.current();
    if (current.isPresent() && current.get().isHeldBy(consumer)) {
      Optional<LeaderLease> renewed = election.renew(current.get(), now, Duration.ofDays(1));
      if (renewed.isPresent()) {
        return renewed.get();
      }
    }
    current.ifPresent(lease -> election.release(lease, now));
    return election
        .tryAcquire(consumer, "host", now, Duration.ofDays(1))
        .orElseThrow(() -> new AssertionError("could not take the leadership for " + consumer));
  }

  private List<String> leaseIds(String consumer, Instant now) {
    return lease(consumer, now, 100).readable().stream().map(StoredMessage::messageId).toList();
  }

  @Test
  @DisplayName("appended entries are leasable after commit only, and vanish on rollback")
  void appendVisibility() {
    LocalTransaction tx = transactions().begin();
    log().append(tx, List.of(event("a", "k1")));
    tx.rollback();
    assertThat(leaseIds("c", T0)).isEmpty();

    appendCommitted(event("b", "k1"));
    assertThat(leaseIds("c", T0)).containsExactly("b");
  }

  @Test
  @DisplayName("a lease returns payloads intact, leases the entry and counts an attempt")
  void leaseRoundTrip() {
    appendCommitted(event("a", "k1"), command("s-9"));

    LeasedMessages leased = lease("c", T0, 10);

    assertThat(leased.readable()).hasSize(2);
    StoredMessage first = leased.readable().get(0);
    assertThat(first.payload()).isEqualTo(new Paid("o-a", 10));
    assertThat(first.attempts()).isEqualTo(1);
    assertThat(first.leasedBy()).isEqualTo("c");
    assertThat(first.leasedUntil()).isEqualTo(T0.plus(LEASE));
    StoredMessage second = leased.readable().get(1);
    assertThat(second.kind()).isEqualTo(MessageKind.COMMAND);
    assertThat(second.commandId()).isEqualTo(CommandId.of("s-9", "step", Paid.class.getName(), 1));
    assertThat(first.position()).isLessThan(second.position());
  }

  @Test
  @DisplayName(
      "only the head of an ordering key is leasable; entries without a key are independent")
  void headOfKey() {
    appendCommitted(
        event("a", "k1"), event("b", "k1"), event("c", "k2"), event("d", null), event("e", null));

    assertThat(leaseIds("c", T0)).containsExactly("a", "c", "d", "e");
    log().markConsumed("c", "a", T0);
    assertThat(leaseIds("c", T0)).containsExactly("b");
  }

  @Test
  @DisplayName("a leased entry is not leased again until its lease expires")
  void leaseExpiry() {
    appendCommitted(event("a", "k1"));
    assertThat(leaseIds("c1", T0)).containsExactly("a");

    assertThat(leaseIds("c2", T0.plusSeconds(60))).isEmpty();
    List<StoredMessage> again = lease("c2", T0.plus(LEASE).plusSeconds(1), 10).readable();
    assertThat(again)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.leasedBy()).isEqualTo("c2");
              assertThat(m.attempts()).isEqualTo(2);
            });
  }

  @Test
  @DisplayName("writes by a consumer that lost the lease are rejected and change nothing")
  void leaseLost() {
    appendCommitted(event("a", "k1"));
    leaseIds("c1", T0);
    leaseIds("c2", T0.plus(LEASE).plusSeconds(1));

    assertThatThrownBy(() -> log().markConsumed("c1", "a", T0))
        .isInstanceOf(LeaseLostException.class);
    assertThatThrownBy(
            () ->
                log().recordFailure("c1", "a", DeliveryFailure.of(new RuntimeException(), T0), T0))
        .isInstanceOf(LeaseLostException.class);
    assertThatThrownBy(
            () -> log().setAside("c1", "a", DeliveryFailure.of(new RuntimeException(), T0), "x"))
        .isInstanceOf(LeaseLostException.class);
    assertThat(log().query(MessageQuery.builder().build()))
        .singleElement()
        .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.UNCONSUMED));
  }

  @Test
  @DisplayName("renew extends held leases and reports lost ones")
  void renew() {
    appendCommitted(event("a", "k1"), event("b", "k2"));
    leaseIds("c1", T0);
    log().markConsumed("c1", "b", T0);

    assertThat(log().renew("c1", List.of("a", "b"), T0.plus(LEASE).plus(LEASE)))
        .containsExactly("b");
    assertThat(leaseIds("c2", T0.plus(LEASE).plusSeconds(1))).isEmpty();
  }

  @Test
  @DisplayName(
      "a recorded failure releases the lease and delays the entry; it keeps blocking its key")
  void recordFailure() {
    appendCommitted(event("a", "k1"), event("b", "k1"));
    leaseIds("c", T0);
    log()
        .recordFailure(
            "c",
            "a",
            DeliveryFailure.of(new IllegalStateException("boom"), T0),
            T0.plusSeconds(10));

    assertThat(leaseIds("c", T0.plusSeconds(5))).isEmpty();
    List<StoredMessage> due = lease("c", T0.plusSeconds(10), 10).readable();
    assertThat(due)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.messageId()).isEqualTo("a");
              assertThat(m.attempts()).isEqualTo(2);
              assertThat(m.lastFailure().exceptionType())
                  .isEqualTo(IllegalStateException.class.getName());
              assertThat(m.lastFailure().message()).isEqualTo("boom");
            });
  }

  @Test
  @DisplayName("a set-aside entry stops blocking its key and can be redelivered")
  void setAsideAndRedeliver() {
    appendCommitted(event("a", "k1"), event("b", "k1"));
    leaseIds("c", T0);
    log()
        .setAside(
            "c", "a", DeliveryFailure.of(new IllegalStateException("bad"), T0), "MAX_ATTEMPTS");

    assertThat(leaseIds("c", T0)).containsExactly("b");
    log().markConsumed("c", "b", T0);
    assertThat(log().redeliver("a", T0.plusSeconds(1))).isTrue();
    assertThat(log().redeliver("b", T0.plusSeconds(1))).isFalse();
    List<StoredMessage> again = lease("c", T0.plusSeconds(1), 10).readable();
    assertThat(again)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.messageId()).isEqualTo("a");
              assertThat(m.attempts()).isEqualTo(1);
              assertThat(m.lastFailure().message()).isEqualTo("bad");
            });
  }

  @Test
  @DisplayName(
      "setAside in a transaction applies only if it commits, and keeps the lease until then (012)")
  void setAsideInTransaction() {
    appendCommitted(event("a", "k1"), event("b", "k1"));
    leaseIds("c", T0);
    DeliveryFailure failure = new DeliveryFailure("com.example.Declined", null, T0);

    LocalTransaction rolledBack = transactions().begin();
    log().setAside(rolledBack, "c", "a", failure, "FINAL_FAILURE");
    rolledBack.rollback();
    assertThat(statusOf("a")).isEqualTo(MessageStatus.UNCONSUMED);
    assertThat(leaseIds("other", T0)).isEmpty();

    inTransaction(tx -> log().setAside(tx, "c", "a", failure, "FINAL_FAILURE"));
    assertThat(statusOf("a")).isEqualTo(MessageStatus.SET_ASIDE);
    assertThat(log().query(MessageQuery.builder().statuses(MessageStatus.SET_ASIDE).build()))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.setAsideReason()).isEqualTo("FINAL_FAILURE");
              assertThat(m.lastFailure()).isEqualTo(failure);
            });
    assertThat(leaseIds("c", T0)).containsExactly("b");
  }

  @Test
  @DisplayName("setAside in a transaction needs the lease")
  void setAsideInTransactionNeedsTheLease() {
    appendCommitted(event("a", "k1"));
    DeliveryFailure failure = new DeliveryFailure("com.example.Declined", null, T0);

    assertThatThrownBy(
            () -> inTransaction(tx -> log().setAside(tx, "c", "a", failure, "FINAL_FAILURE")))
        .isInstanceOf(LeaseLostException.class);
  }

  @Test
  @DisplayName("markConsumed in a transaction applies only if it commits")
  void markConsumedInTransaction() {
    appendCommitted(event("a", "k1"));
    leaseIds("c", T0);

    LocalTransaction rolledBack = transactions().begin();
    log().markConsumed(rolledBack, "c", "a", T0);
    rolledBack.rollback();
    assertThat(statusOf("a")).isEqualTo(MessageStatus.UNCONSUMED);

    inTransaction(tx -> log().markConsumed(tx, "c", "a", T0));
    assertThat(statusOf("a")).isEqualTo(MessageStatus.CONSUMED);
  }

  @Test
  @DisplayName("release frees every lease of a consumer")
  void release() {
    appendCommitted(event("a", "k1"), event("b", "k2"));
    leaseIds("c1", T0);
    log().release("c1");

    assertThat(leaseIds("c2", T0.plusSeconds(1))).containsExactly("a", "b");
  }

  @Test
  @DisplayName("an unreadable payload is still leased, reported apart, and can be set aside")
  void unreadable() {
    appendCommitted(event("a", "k1"), event("b", "k2"));
    makeUnreadable("a");

    LeasedMessages leased = lease("c", T0, 10);

    assertThat(leased.readable()).extracting(StoredMessage::messageId).containsExactly("b");
    assertThat(leased.unreadable())
        .singleElement()
        .satisfies(e -> assertThat(e.leased().messageId()).isEqualTo("a"));
    log().setAside("c", "a", DeliveryFailure.of(leased.unreadable().get(0), T0), "UNREADABLE");
    assertThat(statusOf("a")).isEqualTo(MessageStatus.SET_ASIDE);
  }

  @Test
  @DisplayName("a duplicate message id is rejected")
  void duplicate() {
    appendCommitted(event("a", "k1"));

    assertThatThrownBy(() -> appendCommitted(event("a", "k2")))
        .isInstanceOf(DuplicateMessageException.class);
  }

  @Test
  @DisplayName(
      "019: metadata round-trips through append, lease, failure, set-aside and queries; none"
          + " stays empty")
  void metadata() {
    SagaMetadata metadata = SagaMetadata.of(Map.of("correlationId", "c-1", "tenant", "t-9"));
    appendCommitted(event("m", "k1").withMetadata(metadata), event("n", "k2"));

    LeasedMessages leased = lease("c", T0, 10);

    assertThat(leased.readable())
        .extracting(StoredMessage::metadata)
        .containsExactly(metadata, SagaMetadata.empty());
    log()
        .recordFailure(
            "c", "m", DeliveryFailure.of(new IllegalStateException("boom"), T0), T0.plusSeconds(1));
    assertThat(log().query(MessageQuery.builder().build()))
        .extracting(StoredMessage::metadata)
        .containsExactly(metadata, SagaMetadata.empty());
  }

  @Test
  @DisplayName(
      "010: the trace context round-trips through append, lease and queries; none stays empty")
  void traceContext() {
    Map<String, String> context =
        Map.of(
            "traceparent",
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
            "tracestate",
            "vendor=value");
    NewMessage traced = event("t", "k1");
    appendCommitted(
        new NewMessage(
            traced.messageId(),
            traced.kind(),
            traced.payloadType(),
            traced.payload(),
            traced.commandId(),
            traced.orderingKey(),
            traced.sourceSagaType(),
            traced.sourceSagaId(),
            traced.recordedAt(),
            context),
        event("u", "k2"));

    LeasedMessages leased = lease("c", T0, 10);

    assertThat(leased.readable())
        .extracting(StoredMessage::traceContext)
        .containsExactly(context, Map.of());
    assertThat(log().query(MessageQuery.builder().build()))
        .extracting(StoredMessage::traceContext)
        .containsExactly(context, Map.of());
  }

  @Test
  @DisplayName(
      "queries filter by status and source saga, in position order, without payload by default")
  void query() {
    appendCommitted(event("a", "k1"), event("b", "k2"), command("s-9"));
    leaseIds("c", T0);
    log().markConsumed("c", "a", T0);

    assertThat(log().query(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).build()))
        .extracting(StoredMessage::messageId)
        .hasSize(2)
        .doesNotContain("a");
    assertThat(log().query(MessageQuery.builder().sourceSaga("Saga", "s-1").build()))
        .extracting(StoredMessage::messageId)
        .containsExactly("a", "b");
    assertThat(log().query(MessageQuery.builder().build()))
        .allSatisfy(m -> assertThat(m.hasPayload()).isFalse());
    assertThat(log().query(MessageQuery.builder().includePayload(true).limit(1).build()))
        .singleElement()
        .satisfies(m -> assertThat(m.payload()).isEqualTo(new Paid("o-a", 10)));
  }

  @Test
  @DisplayName("purge removes consumed entries older than the cutoff, in batches, and nothing else")
  void purge() {
    appendCommitted(event("a", "k1"), event("b", "k2"), event("c", "k3"), event("d", "k4"));
    leaseIds("c", T0);
    log().markConsumed("c", "a", T0);
    log().markConsumed("c", "b", T0);
    log().markConsumed("c", "c", T0.plusSeconds(100));
    log().setAside("c", "d", DeliveryFailure.of(new RuntimeException(), T0), "MAX_ATTEMPTS");

    assertThat(log().purgeConsumed(T0.plusSeconds(50), 1)).isEqualTo(1);
    assertThat(log().purgeConsumed(T0.plusSeconds(50), 10)).isEqualTo(1);
    assertThat(log().purgeConsumed(T0.plusSeconds(50), 10)).isZero();
    assertThat(log().query(MessageQuery.builder().build()))
        .extracting(StoredMessage::messageId)
        .containsExactly("c", "d");
  }

  private MessageStatus statusOf(String id) {
    return log().query(MessageQuery.builder().build()).stream()
        .filter(m -> m.messageId().equals(id))
        .findFirst()
        .orElseThrow()
        .status();
  }

  // ---- specs/017-saga-deadlines: scheduling (contracts/message-store-scheduling.md) ----

  protected static NewMessage scheduled(String id, Instant notBefore) {
    return new NewMessage(
        id,
        MessageKind.EVENT,
        Paid.class.getName(),
        new Paid("o-" + id, 1),
        null,
        null,
        "Saga",
        "s-1",
        T0,
        Map.of(),
        notBefore);
  }

  protected static NewMessage scheduledCommand(String sagaId, Instant notBefore) {
    CommandId commandId = CommandId.of(sagaId, "step", Paid.class.getName(), 7);
    return new NewMessage(
        commandId.value(),
        MessageKind.COMMAND,
        Paid.class.getName(),
        new Paid("o-sc", 2),
        commandId,
        null,
        "Saga",
        sagaId,
        T0,
        Map.of(),
        notBefore);
  }

  private java.util.Set<String> cancelCommitted(Instant at, String... ids) {
    java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> result =
        new java.util.concurrent.atomic.AtomicReference<>();
    inTransaction(tx -> result.set(log().cancel(tx, List.of(ids), at)));
    return result.get();
  }

  @Test
  @DisplayName("017: the store supports scheduling")
  void schedulingSupported() {
    assertThat(log().supportsScheduling()).isTrue();
  }

  @Test
  @DisplayName(
      "017: a scheduled message is not leasable before its notBefore, and leasable from then on")
  void notLeasedBeforeDue() {
    Instant due = T0.plus(Duration.ofMinutes(15));
    appendCommitted(scheduled("s1", due), scheduledCommand("saga-1", due));

    assertThat(leaseIds("c", T0)).isEmpty();
    assertThat(leaseIds("c", due.minusMillis(1))).isEmpty();
    assertThat(leaseIds("c", due))
        .containsExactlyInAnyOrder(
            "s1", CommandId.of("saga-1", "step", Paid.class.getName(), 7).value());
  }

  @Test
  @DisplayName("017: a scheduled message appended in a rolled-back transaction is never stored")
  void scheduledRollback() {
    LocalTransaction tx = transactions().begin();
    log().append(tx, List.of(scheduled("s1", T0)));
    tx.rollback();
    assertThat(leaseIds("c", T0.plus(Duration.ofDays(1)))).isEmpty();
  }

  @Test
  @DisplayName("017: a message not yet due never holds back a later message")
  void scheduledDoesNotBlock() {
    appendCommitted(
        scheduled("s1", T0.plus(Duration.ofHours(2))), event("a", "k1"), event("b", null));
    assertThat(leaseIds("c", T0)).containsExactly("a", "b");
  }

  @Test
  @DisplayName("017: overdue messages are all leasable at once, in position order")
  void overdueInPositionOrder() {
    appendCommitted(scheduled("late", T0.plusSeconds(60)), scheduled("early", T0.plusSeconds(10)));
    assertThat(leaseIds("c", T0.plus(Duration.ofDays(3)))).containsExactly("late", "early");
  }

  @Test
  @DisplayName("017: cancel withdraws pending messages, due or not, and reports what it cancelled")
  void cancelPending() {
    appendCommitted(scheduled("future", T0.plus(Duration.ofHours(1))), scheduled("due", T0));

    assertThat(cancelCommitted(T0, "future", "due", "unknown"))
        .containsExactlyInAnyOrder("future", "due");

    assertThat(leaseIds("c", T0.plus(Duration.ofDays(1)))).isEmpty();
    List<StoredMessage> cancelled =
        log().query(MessageQuery.builder().statuses(MessageStatus.CANCELLED).build());
    assertThat(cancelled)
        .extracting(StoredMessage::messageId)
        .containsExactlyInAnyOrder("future", "due");
    assertThat(cancelled).allSatisfy(message -> assertThat(message.consumedAt()).isEqualTo(T0));
  }

  @Test
  @DisplayName("017: cancel leaves leased, consumed, set-aside and cancelled messages unchanged")
  void cancelLeavesOthers() {
    appendCommitted(
        scheduled("leased", T0),
        scheduled("consumed", T0),
        scheduled("aside", T0),
        scheduled("twice", T0.plusSeconds(60)));
    lease("c", T0, 100);
    log().markConsumed("c", "consumed", T0);
    log().setAside("c", "aside", new DeliveryFailure("x.Y", "boom", T0), "MAX_ATTEMPTS");
    cancelCommitted(T0, "twice");

    assertThat(cancelCommitted(T0, "leased", "consumed", "aside", "twice")).isEmpty();
    assertThat(log().query(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).build()))
        .extracting(StoredMessage::messageId)
        .containsExactly("leased");
  }

  @Test
  @DisplayName("017: a message whose lease expired can be cancelled")
  void cancelAfterLeaseExpiry() {
    appendCommitted(scheduled("s1", T0));
    lease("c", T0, 100);
    assertThat(cancelCommitted(T0.plus(LEASE).plusSeconds(1), "s1")).containsExactly("s1");
  }

  @Test
  @DisplayName("017: a cancel in a rolled-back transaction cancels nothing")
  void cancelRollback() {
    appendCommitted(scheduled("s1", T0));
    LocalTransaction tx = transactions().begin();
    log().cancel(tx, List.of("s1"), T0);
    tx.rollback();
    assertThat(leaseIds("c", T0)).containsExactly("s1");
  }

  @Test
  @DisplayName(
      "017: leaseholder writes on a cancelled message lose the lease, and retention removes it")
  void cancelledIsTerminal() {
    appendCommitted(scheduled("s1", T0));
    lease("c", T0, 100);
    cancelCommitted(T0.plus(LEASE).plusSeconds(1), "s1");

    assertThatThrownBy(() -> log().markConsumed("c", "s1", T0))
        .isInstanceOf(LeaseLostException.class);
    assertThat(log().purgeConsumed(T0.plus(Duration.ofDays(1)), 100)).isEqualTo(1);
    assertThat(log().query(MessageQuery.builder().build())).isEmpty();
  }

  // ---- specs/013-active-standby-relay: leadership (contracts/leader-election-spi.md) ----

  private Optional<LeaderLease> acquire(String candidate, Instant now) {
    return log().leaderElection().tryAcquire(candidate, "host-" + candidate, now, LEADERSHIP);
  }

  @Test
  @DisplayName("013 C1: the first acquisition on an empty store gets token 1 and is current")
  void acquireOnEmpty() {
    assertThat(log().leaderElection().current()).isEmpty();

    LeaderLease lease = acquire("A", T0).orElseThrow();

    assertThat(lease).isEqualTo(new LeaderLease("A", "host-A", 1, T0, T0.plus(LEADERSHIP)));
    assertThat(log().leaderElection().current()).contains(lease);
    assertThat(log().leaderElection()).isSameAs(log().leaderElection());
  }

  @Test
  @DisplayName("013 C2: no one acquires an unexpired leadership, not even its holder")
  void acquireRefusedWhileHeld() {
    acquire("A", T0);

    assertThat(acquire("B", T0.plus(LEADERSHIP).minusMillis(1))).isEmpty();
    assertThat(acquire("A", T0.plusSeconds(1))).isEmpty();
  }

  @Test
  @DisplayName("013 C3: an expired leadership is acquired with a higher token")
  void acquireAfterExpiry() {
    LeaderLease first = acquire("A", T0).orElseThrow();

    LeaderLease second = acquire("B", T0.plus(LEADERSHIP)).orElseThrow();

    assertThat(second.holderId()).isEqualTo("B");
    assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
    assertThat(log().leaderElection().current()).contains(second);
  }

  @Test
  @DisplayName("013 C4: the holder renews with the same token")
  void renewByHolder() {
    LeaderLease lease = acquire("A", T0).orElseThrow();

    LeaderLease renewed =
        log().leaderElection().renew(lease, T0.plusSeconds(10), LEADERSHIP).orElseThrow();

    assertThat(renewed.fencingToken()).isEqualTo(lease.fencingToken());
    assertThat(renewed.acquiredAt()).isEqualTo(T0);
    assertThat(renewed.expiresAt()).isEqualTo(T0.plusSeconds(10).plus(LEADERSHIP));
    assertThat(acquire("B", T0.plus(LEADERSHIP))).isEmpty();
  }

  @Test
  @DisplayName("013 C5: a renewal after a takeover fails and leaves the new holder current")
  void renewAfterTakeover() {
    LeaderLease old = acquire("A", T0).orElseThrow();
    LeaderLease successor = acquire("B", T0.plus(LEADERSHIP)).orElseThrow();

    assertThat(log().leaderElection().renew(old, T0.plus(LEADERSHIP), LEADERSHIP)).isEmpty();
    assertThat(log().leaderElection().current()).contains(successor);
  }

  @Test
  @DisplayName("013 C6: an expired lease is not renewed, even with nobody else holding it")
  void renewAfterExpiry() {
    LeaderLease lease = acquire("A", T0).orElseThrow();

    assertThat(log().leaderElection().renew(lease, T0.plus(LEADERSHIP), LEADERSHIP)).isEmpty();
    assertThat(acquire("A", T0.plus(LEADERSHIP)).orElseThrow().fencingToken())
        .isGreaterThan(lease.fencingToken());
  }

  @Test
  @DisplayName("013 C7: a release lets another relay acquire at once")
  void releaseHandsOver() {
    LeaderLease lease = acquire("A", T0).orElseThrow();

    log().leaderElection().release(lease, T0.plusSeconds(3));

    LeaderLease next = acquire("B", T0.plusSeconds(3)).orElseThrow();
    assertThat(next.fencingToken()).isGreaterThan(lease.fencingToken());
  }

  @Test
  @DisplayName("013 C8: releasing a stale lease changes nothing")
  void releaseStale() {
    LeaderLease old = acquire("A", T0).orElseThrow();
    LeaderLease successor = acquire("B", T0.plus(LEADERSHIP)).orElseThrow();

    log().leaderElection().release(old, T0.plus(LEADERSHIP).plusSeconds(1));

    assertThat(log().leaderElection().current()).contains(successor);
    assertThat(acquire("C", T0.plus(LEADERSHIP).plusSeconds(1))).isEmpty();
  }

  @Test
  @DisplayName("013 C9: a lease with a stale token is refused and changes no message")
  void leaseWithStaleToken() {
    appendCommitted(event("a", "k1"));
    LeaderLease old = acquire("A", T0).orElseThrow();
    acquire("B", T0.plus(LEADERSHIP));

    assertThatThrownBy(() -> log().lease(old, T0.plus(LEADERSHIP), LEASE, 10))
        .isInstanceOf(LeadershipLostException.class)
        .satisfies(e -> assertThat(((LeadershipLostException) e).staleLease()).isEqualTo(old));
    assertThat(log().query(MessageQuery.builder().build()))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.attempts()).isZero();
              assertThat(m.leasedBy()).isNull();
            });
  }

  @Test
  @DisplayName("013 C10: a lease with an expired leadership is refused")
  void leaseWithExpiredLeadership() {
    appendCommitted(event("a", "k1"));
    LeaderLease lease = acquire("A", T0).orElseThrow();

    assertThatThrownBy(() -> log().lease(lease, T0.plus(LEADERSHIP), LEASE, 10))
        .isInstanceOf(LeadershipLostException.class);
    assertThat(log().lease(lease, T0.plus(LEADERSHIP).minusMillis(1), LEASE, 10).readable())
        .extracting(StoredMessage::leasedBy)
        .containsExactly("A");
  }

  @Test
  @DisplayName("013 C11: of two concurrent acquisitions exactly one succeeds")
  void concurrentAcquisition() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 10; round++) {
        Instant now = T0.plus(LEADERSHIP.multipliedBy(round));
        CountDownLatch start = new CountDownLatch(1);
        Future<Optional<LeaderLease>> first =
            pool.submit(
                () -> {
                  start.await();
                  return acquire("A", now);
                });
        Future<Optional<LeaderLease>> second =
            pool.submit(
                () -> {
                  start.await();
                  return acquire("B", now);
                });
        start.countDown();

        assertThat(first.get().isPresent() ^ second.get().isPresent())
            .as("round %d: exactly one winner", round)
            .isTrue();
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
