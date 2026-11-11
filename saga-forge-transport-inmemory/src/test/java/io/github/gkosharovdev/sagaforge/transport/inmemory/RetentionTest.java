package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.MutableClock;

/** FR-019 (tasks.md T065): consumed messages are kept 7 days, then removed; nothing else is ever removed. */
class RetentionTest {

    @Test
    @DisplayName("consumed messages older than the retention are removed; set-aside and unconsumed are kept")
    void retention() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
        InMemoryMessageStore log = new InMemoryMessageStore();
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        InMemoryTransport transport = InMemoryTransport.builder().messageStore(log).transactions(boundary).clock(clock)
                .settings(TransportSettings.defaults().withMaxAttempts(1).withPollInterval(Duration.ofMillis(20)))
                .build();
        transport.start(event -> {
            if (((Unrouted) event).orderId().equals("poison")) {
                throw new IllegalStateException("poison");
            }
            return new HandlingReport(event, List.of());
        }, null);
        try {
            transport.dispatcher().dispatch(new Unrouted("old"));
            transport.dispatcher().dispatch(new Unrouted("poison"));
            await(() -> log.all().stream().allMatch(m -> m.status() != MessageStatus.UNCONSUMED),
                    Duration.ofSeconds(5), "settled");
            clock.advance(Duration.ofDays(6));
            transport.dispatcher().dispatch(new Unrouted("recent"));
            await(() -> log.all().stream().allMatch(m -> m.status() != MessageStatus.UNCONSUMED),
                    Duration.ofSeconds(5), "settled again");
            log.pauseLeasing(true);
            transport.dispatcher().dispatch(new Unrouted("waiting"));
            clock.advance(Duration.ofDays(2));

            assertThat(transport.sweepRetention()).isEqualTo(1);

            assertThat(log.all()).extracting(m -> ((Unrouted) m.payload()).orderId())
                    .containsExactly("poison", "recent", "waiting");
            assertThat(log.all()).extracting(StoredMessage::status).containsExactly(MessageStatus.SET_ASIDE,
                    MessageStatus.CONSUMED, MessageStatus.UNCONSUMED);
        } finally {
            transport.stop(Duration.ofSeconds(2));
        }
    }
}
