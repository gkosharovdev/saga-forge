package io.github.gkosharovdev.sagaforge.storage.postgres;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/**
 * Deadlines over PostgreSQL across a restart (specs/017-saga-deadlines SC-001, SC-004; tasks.md T027): a deadline
 * that came due while the process was down runs its timeout step once after the restart, and 10,000 overdue deadlines
 * are all delivered, none twice.
 */
@Tag("container")
class DeadlineRestartContainerTest {

    private static final int OVERDUE = 10_000;

    @BeforeEach
    void resetDatabase() {
        PostgresTestSupport.reset();
    }

    private static List<DeadlineReached> deadlinesDelivered(PostgresTransportHarness harness) {
        return harness.receiver().delivered().stream().filter(DeadlineReached.class::isInstance)
                .map(DeadlineReached.class::cast).toList();
    }

    @Test
    @DisplayName("SC-001: a deadline due while the process was down runs its timeout step exactly once after restart")
    void dueWhileDown() throws InterruptedException {
        PostgresTransportHarness harness = new PostgresTransportHarness(TransportHarness.FAST, HoldSaga.class);
        harness.start();
        harness.publish(new HoldPlaced("h-1"));
        await(() -> !harness.messagesOf(DeadlineReached.class).isEmpty(), Duration.ofSeconds(10), "the deadline");
        harness.close();
        assertThat(PostgresTransportHarness.hasInstance(HoldSaga.class, "RUNNING")).isTrue();

        Thread.sleep(500);
        harness.assemble().start();
        try {
            await(() -> PostgresTransportHarness.hasInstance(HoldSaga.class, "COMPENSATED"), Duration.ofSeconds(10),
                    "the hold to lapse after the restart");
            harness.awaitQuiet(Duration.ofSeconds(10));
            assertThat(deadlinesDelivered(harness)).hasSize(1);
            assertThat(harness.messagesOf(DeadlineReached.class)).singleElement()
                    .satisfies(message -> assertThat(message.status()).isEqualTo(MessageStatus.CONSUMED));
        } finally {
            harness.close();
        }
    }

    @Test
    @DisplayName("SC-004: 10,000 overdue deadlines at restart are all delivered, none lost, none run twice")
    void overdueBacklog() throws InterruptedException {
        TransportSettings settings = TransportSettings.defaults().withWorkers(8);
        PostgresTransportHarness harness = new PostgresTransportHarness(settings, HoldSaga.class);
        // The process starts every hold, its relay stopped, and goes down before any comes due.
        java.util.stream.IntStream.range(0, OVERDUE).parallel()
                .forEach(i -> harness.engineReceive(new HoldPlaced("h-" + i)));
        harness.close();
        Thread.sleep(500);

        harness.assemble().start();
        try {
            await(() -> PostgresTestSupport.queryRows(
                    "SELECT count(*) AS n FROM saga_instance WHERE status = 'COMPENSATED'").get(0).get("n")
                    .toString().equals(String.valueOf(OVERDUE)), Duration.ofSeconds(180), "every hold to lapse");
            // The receiver records a delivery after the unit of work that compensated the instance committed, so the
            // last one may follow the COMPENSATED count by a moment.
            await(() -> deadlinesDelivered(harness).size() == OVERDUE, Duration.ofSeconds(30), "every delivery to record");
            List<DeadlineReached> delivered = deadlinesDelivered(harness);
            assertThat(delivered).hasSize(OVERDUE);
            assertThat(delivered.stream().map(DeadlineReached::deadlineId).distinct()).hasSize(OVERDUE);
            assertThat(PostgresTestSupport.queryRows("SELECT status, count(*) AS n FROM saga_message_store"
                    + " WHERE payload_type = ? GROUP BY status", DeadlineReached.class.getName()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.get("status")).isEqualTo("CONSUMED");
                        assertThat(row.get("n").toString()).isEqualTo(String.valueOf(OVERDUE));
                    });
        } finally {
            harness.close();
        }
    }
}
