package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportUnderTest;

/** Failure handling (FR-014 to FR-016, SC-006, tasks.md T039), without an engine. */
class FailureHandlingTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final TransportSettings SETTINGS = TransportSettings.defaults().withWorkers(2).withMaxAttempts(10)
            .withBackoff(Duration.ofMillis(1), Duration.ofMillis(4)).withPollInterval(Duration.ofMillis(20));

    private static HandlingReport failFor(Object event, String poisonId) {
        if (((Unrouted) event).orderId().equals(poisonId)) {
            throw new IllegalStateException("poison " + poisonId);
        }
        return new HandlingReport(event, List.of());
    }

    private static StoredMessage only(TransportUnderTest t, String orderId) {
        return t.log.all().stream().filter(m -> ((Unrouted) m.payload()).orderId().equals(orderId)).findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("SC-006: a message that always fails is set aside after the 10th attempt with its last failure")
    void setAsideAfterMaxAttempts() {
        try (TransportUnderTest t = new TransportUnderTest(SETTINGS, event -> failFor(event, "poison")).start()) {
            t.publish(new Unrouted("poison"));

            await(() -> only(t, "poison").status() == MessageStatus.SET_ASIDE, WAIT, "the message to be set aside");
            StoredMessage poison = only(t, "poison");
            assertThat(poison.attempts()).isEqualTo(10);
            assertThat(poison.setAsideReason()).isEqualTo("MAX_ATTEMPTS");
            assertThat(poison.lastFailure().message()).isEqualTo("poison poison");
            assertThat(t.receiver.deliveries()).hasSize(10);
            await(() -> t.logger.byEvent("saga.transport.setAside").size() == 1, WAIT, "the set-aside warning");
        }
    }

    @Test
    @DisplayName("FR-016: other aggregates keep flowing while one retries, and a set-aside message unblocks its key")
    void otherKeysFlowAndSetAsideUnblocks() {
        TransportSettings slowRetries = SETTINGS.withBackoff(Duration.ofMillis(30), Duration.ofMillis(30));
        try (TransportUnderTest t = new TransportUnderTest(slowRetries, event -> failFor(event, "poison")).start()) {
            t.publish(new Unrouted("poison"));
            for (int i = 0; i < 20; i++) {
                t.publish(new Unrouted("ok-" + i));
            }

            await(() -> t.receiver.delivered().size() == 20, WAIT, "the healthy messages");
            assertThat(only(t, "poison").status()).isEqualTo(MessageStatus.UNCONSUMED);
            await(() -> only(t, "poison").status() == MessageStatus.SET_ASIDE, WAIT, "the poison to be set aside");
        }
    }

    @Test
    @DisplayName("a later message of a key waits for the head's retries and goes once the head is set aside")
    void sameKeyWaitsForHead() {
        Unrouted head = new Unrouted("poison");
        Unrouted next = new Unrouted("poison");
        try (TransportUnderTest t = new TransportUnderTest(SETTINGS.withMaxAttempts(3), event -> {
            if (event == head) {
                throw new IllegalStateException("head fails");
            }
            return new HandlingReport(event, List.of());
        }).start()) {
            t.publish(head);
            t.publish(next);

            await(() -> t.log.all().get(1).status() == MessageStatus.CONSUMED, WAIT, "the second message");
            assertThat(t.log.all().get(0).status()).isEqualTo(MessageStatus.SET_ASIDE);
            assertThat(t.log.all().get(0).attempts()).isEqualTo(3);
            assertThat(t.log.all().get(1).attempts()).isEqualTo(1);
            assertThat(t.receiver.deliveries()).extracting(d -> d.event() == head)
                    .containsExactly(true, true, true, false);
        }
    }

    @Test
    @DisplayName("an unreadable payload is set aside at once, without a delivery")
    void unreadableSetAsideAtOnce() {
        try (TransportUnderTest t = new TransportUnderTest(SETTINGS, event -> new HandlingReport(event, List.of()))) {
            t.publish(new Unrouted("gone"));
            t.log.markUnreadable(t.log.all().get(0).messageId());
            t.start();

            await(() -> t.log.all().get(0).status() == MessageStatus.SET_ASIDE, WAIT, "the message to be set aside");
            assertThat(t.log.all().get(0).setAsideReason()).startsWith("UNREADABLE");
            assertThat(t.receiver.deliveries()).isEmpty();
        }
    }

    @Test
    @DisplayName("FR-017: any report from the receiver consumes the message; only exceptions retry")
    void anyReportConsumes() {
        try (TransportUnderTest t = new TransportUnderTest(SETTINGS, event -> new HandlingReport(event, List.of()))
                .start()) {
            t.publish(new Unrouted("fine"));

            await(() -> t.log.all().get(0).status() == MessageStatus.CONSUMED, WAIT, "consumption");
            assertThat(t.log.all().get(0).attempts()).isEqualTo(1);
        }
    }

}
