package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;

/**
 * SC-003 (tasks.md T032): on an idle transport with the default 500 ms poll, a committed message is delivered within
 * 100 ms in at least 99% of 300 sequential publications, because commit signals the relay.
 */
class DeliveryLatencyTest {

    @Test
    @DisplayName("SC-003: 99% of messages are delivered within 100 ms of commit")
    void deliveredPromptlyAfterCommit() {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        InMemoryTransport transport = InMemoryTransport.builder().messageStore(new InMemoryMessageStore()).transactions(boundary)
                .build();
        AtomicLong delivered = new AtomicLong();
        List<Long> latenciesMillis = Collections.synchronizedList(new ArrayList<>());
        long[] publishedAt = new long[1];
        transport.start(event -> {
            latenciesMillis.add((System.nanoTime() - publishedAt[0]) / 1_000_000);
            delivered.incrementAndGet();
            return new HandlingReport(event, List.of());
        }, null);
        try {
            for (int i = 0; i < 300; i++) {
                long expected = i + 1;
                publishedAt[0] = System.nanoTime();
                transport.dispatcher().dispatch(new Unrouted("o-" + i));
                await(() -> delivered.get() == expected, Duration.ofSeconds(2), "delivery " + expected);
            }
        } finally {
            transport.stop(Duration.ofSeconds(2));
        }
        List<Long> sorted = new ArrayList<>(latenciesMillis);
        Collections.sort(sorted);
        assertThat(sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1)).isLessThanOrEqualTo(100L);
    }
}
