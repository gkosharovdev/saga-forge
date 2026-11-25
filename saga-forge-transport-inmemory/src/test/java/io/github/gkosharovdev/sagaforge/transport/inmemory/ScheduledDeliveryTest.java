package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/**
 * The transport's outbox stores scheduled messages and cancels them in the unit of work's transaction
 * (specs/017-saga-deadlines contracts/outbox-and-gateway.md; tasks.md T024).
 */
class ScheduledDeliveryTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final TransportHarness harness = new TransportHarness(TransportHarness.FAST, HoldSaga.class);

    @AfterEach
    void stop() {
        harness.close();
    }

    private StoredMessage deadline() {
        return harness.messagesOf(DeadlineReached.class).get(0);
    }

    @Test
    @DisplayName("the outbox supports scheduling when its message store does")
    void supportsScheduling() {
        assertThat(harness.transport().outbox().supportsScheduling()).isTrue();
    }

    @Test
    @DisplayName("a deadline is stored with its due time and no ordering key, held back until due, then runs once")
    void heldBackUntilDue() {
        harness.start();
        harness.publish(new HoldPlaced("h-1"));

        await(() -> !harness.messagesOf(DeadlineReached.class).isEmpty(), WAIT, "the deadline to be stored");
        StoredMessage stored = deadline();
        assertThat(stored.kind()).isEqualTo(MessageKind.EVENT);
        assertThat(stored.orderingKey()).isNull();
        assertThat(stored.sourceSagaType()).isEqualTo(HoldSaga.class.getName());
        assertThat(stored.nextAttemptAt()).isEqualTo(((DeadlineReached) stored.payload()).dueAt());

        await(() -> harness.instance(HoldSaga.class, SagaStatus.COMPENSATED).isPresent(), WAIT, "the hold to lapse");
        assertThat(java.time.Instant.now()).isAfterOrEqualTo(((DeadlineReached) stored.payload()).dueAt());
        assertThat(deadline().status()).isEqualTo(MessageStatus.CONSUMED);
    }

    @Test
    @DisplayName("leaving RUNNING cancels the stored deadline in the same transaction; it never runs")
    void cancelledOnEnd() throws InterruptedException {
        harness.engine().receive(new HoldPlaced("h-1"));
        harness.engine().receive(new HoldConfirmed("h-1"));
        harness.start();

        assertThat(deadline().status()).isEqualTo(MessageStatus.CANCELLED);
        Thread.sleep(500);
        assertThat(harness.instance(HoldSaga.class, SagaStatus.COMPLETED)).isPresent();
        assertThat(harness.instance(HoldSaga.class, SagaStatus.COMPENSATED)).isEmpty();
    }
}
