package io.github.gkosharovdev.sagaforge.storage.postgres;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.log.LogEntry;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;

/** SC-005 (specs/007 tasks.md T050): 10,000 unconsumed messages are delivered within 60 s of a restart. */
@Tag("container")
class RestartBacklogContainerTest {

    private static final int BACKLOG = 10_000;

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
    }

    @Test
    @DisplayName("SC-005: a 10,000-message backlog is consumed within 60 s, and new work flows meanwhile")
    void backlogDeliveredWithinAMinute() {
        DataSource dataSource = PostgresTestSupport.newDataSource();
        JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(dataSource);
        PostgresMessageLog log = new PostgresMessageLog(dataSource, boundary);
        LocalTransaction tx = boundary.begin();
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < BACKLOG; i++) {
            entries.add(new LogEntry("m-" + i, MessageKind.EVENT, Unrouted.class.getName(), new Unrouted("o-" + i),
                    null, Unrouted.class.getName() + "#o-" + (i % 500), null, null, java.time.Instant.now()));
        }
        log.append(tx, entries);
        tx.commit();
        AtomicInteger delivered = new AtomicInteger();
        InMemoryTransport transport = InMemoryTransport.builder().log(log).transactions(boundary)
                .settings(TransportSettings.defaults().withWorkers(8)).build();

        long started = System.nanoTime();
        transport.start(event -> {
            delivered.incrementAndGet();
            return new HandlingReport(event, List.of());
        }, null);
        try {
            transport.dispatcher().dispatch(new Unrouted("new-work"));
            await(() -> delivered.get() == BACKLOG + 1, Duration.ofSeconds(60), "the backlog");
            long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();
            assertThat(seconds).isLessThan(60);
            assertThat(log.query(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).build())).isEmpty();
        } finally {
            transport.stop(Duration.ofSeconds(5));
        }
    }
}
