package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.ConsumerHeartbeat;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.DuplicateMessageException;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaseLostException;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;

/**
 * The {@link MessageStore} contract ({@code specs/007-in-memory-transport} contracts/core-spi.md, data-model.md), run
 * against every implementation: {@code InMemoryMessageStoreTest} here and the Postgres container suite.
 *
 * <p>Payloads are {@link Paid} records, which every implementation must round-trip. Subclasses supply a fresh log
 * per test, the boundary its transactions join, and a way to make a message unreadable.</p>
 */
public abstract class MessageStoreContract {

    /** A payload every implementation can encode. */
    public record Paid(String orderId, long amount) {
    }

    protected static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");
    protected static final Duration LEASE = Duration.ofMinutes(5);

    /** @return the store under test, empty */
    protected abstract MessageStore log();

    /** @return the boundary whose transactions {@link #log()} joins */
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
        return new NewMessage(id, MessageKind.EVENT, Paid.class.getName(), new Paid("o-" + id, 10), null, key, "Saga",
                "s-1", T0);
    }

    protected static NewMessage command(String sagaId) {
        CommandId commandId = CommandId.of(sagaId, "step", Paid.class.getName(), 1);
        return new NewMessage(commandId.value(), MessageKind.COMMAND, Paid.class.getName(), new Paid("o-c", 5),
                commandId, "saga:" + sagaId, null, null, T0);
    }

    private List<String> leaseIds(String consumer, Instant now) {
        return log().lease(consumer, now, LEASE, 100).readable().stream().map(StoredMessage::messageId).toList();
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

        LeasedMessages leased = log().lease("c", T0, LEASE, 10);

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
    @DisplayName("only the head of an ordering key is leasable; entries without a key are independent")
    void headOfKey() {
        appendCommitted(event("a", "k1"), event("b", "k1"), event("c", "k2"), event("d", null), event("e", null));

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
        List<StoredMessage> again = log().lease("c2", T0.plus(LEASE).plusSeconds(1), LEASE, 10).readable();
        assertThat(again).singleElement().satisfies(m -> {
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

        assertThatThrownBy(() -> log().markConsumed("c1", "a", T0)).isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> log().recordFailure("c1", "a", DeliveryFailure.of(new RuntimeException(), T0), T0))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> log().setAside("c1", "a", DeliveryFailure.of(new RuntimeException(), T0), "x"))
                .isInstanceOf(LeaseLostException.class);
        assertThat(log().query(MessageQuery.builder().build())).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.UNCONSUMED));
    }

    @Test
    @DisplayName("renew extends held leases and reports lost ones")
    void renew() {
        appendCommitted(event("a", "k1"), event("b", "k2"));
        leaseIds("c1", T0);
        log().markConsumed("c1", "b", T0);

        assertThat(log().renew("c1", List.of("a", "b"), T0.plus(LEASE).plus(LEASE))).containsExactly("b");
        assertThat(leaseIds("c2", T0.plus(LEASE).plusSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("a recorded failure releases the lease and delays the entry; it keeps blocking its key")
    void recordFailure() {
        appendCommitted(event("a", "k1"), event("b", "k1"));
        leaseIds("c", T0);
        log().recordFailure("c", "a", DeliveryFailure.of(new IllegalStateException("boom"), T0), T0.plusSeconds(10));

        assertThat(leaseIds("c", T0.plusSeconds(5))).isEmpty();
        List<StoredMessage> due = log().lease("c", T0.plusSeconds(10), LEASE, 10).readable();
        assertThat(due).singleElement().satisfies(m -> {
            assertThat(m.messageId()).isEqualTo("a");
            assertThat(m.attempts()).isEqualTo(2);
            assertThat(m.lastFailure().exceptionType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(m.lastFailure().message()).isEqualTo("boom");
        });
    }

    @Test
    @DisplayName("a set-aside entry stops blocking its key and can be redelivered")
    void setAsideAndRedeliver() {
        appendCommitted(event("a", "k1"), event("b", "k1"));
        leaseIds("c", T0);
        log().setAside("c", "a", DeliveryFailure.of(new IllegalStateException("bad"), T0), "MAX_ATTEMPTS");

        assertThat(leaseIds("c", T0)).containsExactly("b");
        log().markConsumed("c", "b", T0);
        assertThat(log().redeliver("a", T0.plusSeconds(1))).isTrue();
        assertThat(log().redeliver("b", T0.plusSeconds(1))).isFalse();
        List<StoredMessage> again = log().lease("c", T0.plusSeconds(1), LEASE, 10).readable();
        assertThat(again).singleElement().satisfies(m -> {
            assertThat(m.messageId()).isEqualTo("a");
            assertThat(m.attempts()).isEqualTo(1);
            assertThat(m.lastFailure().message()).isEqualTo("bad");
        });
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

        LeasedMessages leased = log().lease("c", T0, LEASE, 10);

        assertThat(leased.readable()).extracting(StoredMessage::messageId).containsExactly("b");
        assertThat(leased.unreadable()).singleElement()
                .satisfies(e -> assertThat(e.leased().messageId()).isEqualTo("a"));
        log().setAside("c", "a", DeliveryFailure.of(leased.unreadable().get(0), T0), "UNREADABLE");
        assertThat(statusOf("a")).isEqualTo(MessageStatus.SET_ASIDE);
    }

    @Test
    @DisplayName("a duplicate message id is rejected")
    void duplicate() {
        appendCommitted(event("a", "k1"));

        assertThatThrownBy(() -> appendCommitted(event("a", "k2"))).isInstanceOf(DuplicateMessageException.class);
    }

    @Test
    @DisplayName("queries filter by status and source saga, in position order, without payload by default")
    void query() {
        appendCommitted(event("a", "k1"), event("b", "k2"), command("s-9"));
        leaseIds("c", T0);
        log().markConsumed("c", "a", T0);

        assertThat(log().query(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).build()))
                .extracting(StoredMessage::messageId).hasSize(2).doesNotContain("a");
        assertThat(log().query(MessageQuery.builder().sourceSaga("Saga", "s-1").build()))
                .extracting(StoredMessage::messageId).containsExactly("a", "b");
        assertThat(log().query(MessageQuery.builder().build())).allSatisfy(m -> assertThat(m.hasPayload()).isFalse());
        assertThat(log().query(MessageQuery.builder().includePayload(true).limit(1).build())).singleElement()
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
        assertThat(log().query(MessageQuery.builder().build())).extracting(StoredMessage::messageId)
                .containsExactly("c", "d");
    }

    @Test
    @DisplayName("heartbeats are upserted and listed by last-seen time")
    void heartbeats() {
        log().heartbeat(new ConsumerHeartbeat("c1", "host", T0, T0));
        log().heartbeat(new ConsumerHeartbeat("c1", "host", T0, T0.plusSeconds(30)));
        log().heartbeat(new ConsumerHeartbeat("c2", "host", T0, T0));

        assertThat(log().liveConsumers(T0.plusSeconds(10))).extracting(ConsumerHeartbeat::consumerId)
                .containsExactly("c1");
    }

    private MessageStatus statusOf(String id) {
        return log().query(MessageQuery.builder().build()).stream().filter(m -> m.messageId().equals(id))
                .findFirst().orElseThrow().status();
    }
}
