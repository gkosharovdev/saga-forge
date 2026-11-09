package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency.SignalWorld;
import io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency.TicketOpened;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Property 25: concurrent start events with the same association value start exactly one instance in one engine
 * (specs/004-saga-engine tasks.md T079, quickstart.md scenario 13).
 *
 * <p>**Validates: Requirements FR-027, SC-012**</p>
 */
class ConcurrentDuplicateStartTest {

    private static final int THREADS = 8;
    private static final long TIMEOUT_SECONDS = 10;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void shutDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @RepeatedTest(100)
    @DisplayName("eight concurrent TicketOpened with one ticketId: one started, seven DUPLICATE_START")
    void exactlyOneStarts() throws Exception {
        SignalWorld world = new SignalWorld();
        SagaEngine engine = world.engine();
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Future<HandlingReport>> futures = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            futures.add(executor.submit(() -> {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return engine.receive(new TicketOpened("ticket-1"));
            }));
        }

        List<SagaHandlingResult> results = new ArrayList<>();
        for (Future<HandlingReport> future : futures) {
            results.addAll(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).results());
        }

        assertThat(results).hasSize(THREADS);
        assertThat(results).filteredOn(SagaHandlingResult::started).singleElement()
                .satisfies(started -> assertThat(started.outcome()).isEqualTo(HandlingOutcome.WAITING));
        assertThat(results).filteredOn(result -> !result.started()).hasSize(THREADS - 1).allSatisfy(duplicate -> {
            assertThat(duplicate.outcome()).isEqualTo(HandlingOutcome.DUPLICATE_START);
            assertThat(duplicate.sagaId()).isNull();
        });
        assertThat(world.storage().committed().all()).hasSize(1);
    }
}
