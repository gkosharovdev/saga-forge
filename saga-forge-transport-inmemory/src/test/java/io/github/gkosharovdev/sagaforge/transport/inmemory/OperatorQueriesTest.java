package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportUnderTest;

/** User Story 4 (FR-022, FR-023, SC-007, tasks.md T065): what the store holds, and redelivery of set-aside messages. */
class OperatorQueriesTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    @DisplayName("scenarios 1 and 2: every status is reported with its attributes; a set-aside message is redelivered")
    void queriesAndRedelivery() {
        AtomicBoolean poisonHealed = new AtomicBoolean();
        TransportSettings settings = TransportSettings.defaults().withMaxAttempts(2)
                .withBackoff(Duration.ofMillis(1), Duration.ofMillis(1)).withPollInterval(Duration.ofMillis(20));
        try (TransportUnderTest t = new TransportUnderTest(settings, event -> {
            if (((Unrouted) event).orderId().equals("poison") && !poisonHealed.get()) {
                throw new IllegalStateException("cannot handle poison");
            }
            return new HandlingReport(event, List.of());
        }).start()) {
            t.publish(new Unrouted("fine"));
            t.publish(new Unrouted("poison"));
            await(() -> t.log.all().stream().allMatch(m -> m.status() != MessageStatus.UNCONSUMED), WAIT, "settled");
            t.log.pauseLeasing(true);
            t.publish(new Unrouted("waiting"));
            MessageStoreQueries queries = t.transport.queries();

            List<StoredMessage> setAside = queries.find(MessageQuery.builder().statuses(MessageStatus.SET_ASIDE)
                    .build());
            assertThat(setAside).singleElement().satisfies(m -> {
                assertThat(m.payloadType()).isEqualTo(Unrouted.class.getName());
                assertThat(m.attempts()).isEqualTo(2);
                assertThat(m.lastFailure().message()).isEqualTo("cannot handle poison");
                assertThat(m.setAsideReason()).isEqualTo("MAX_ATTEMPTS");
                assertThat(m.hasPayload()).isFalse();
            });
            assertThat(queries.find(MessageQuery.builder().statuses(MessageStatus.CONSUMED).build())).hasSize(1);
            assertThat(queries.find(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).build())).hasSize(1);
            MessageStoreQueries.Status status = queries.status();
            assertThat(status.counts()).containsEntry(MessageStatus.CONSUMED, 1)
                    .containsEntry(MessageStatus.SET_ASIDE, 1).containsEntry(MessageStatus.UNCONSUMED, 1);
            assertThat(status.oldestUnconsumed()).isPresent();

            t.log.pauseLeasing(false);
            poisonHealed.set(true);
            assertThat(queries.redeliver(setAside.get(0).messageId())).isTrue();
            assertThat(queries.redeliver(setAside.get(0).messageId())).isFalse();

            await(() -> t.log.all().stream().allMatch(m -> m.status() == MessageStatus.CONSUMED), WAIT,
                    "everything consumed");
            assertThat(t.log.get(setAside.get(0).messageId()).lastFailure().message())
                    .isEqualTo("cannot handle poison");
        }
    }
}
