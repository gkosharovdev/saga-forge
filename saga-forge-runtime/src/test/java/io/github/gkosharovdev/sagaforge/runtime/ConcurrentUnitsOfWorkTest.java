package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalA;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalB;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.SignalWorld;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.TicketClosed;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.concurrency.TicketOpened;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Property 28: units of work of one instance never overlap, in one engine or across engines sharing a store; units of
 * work of different instances do not wait for each other (specs/004-saga-engine tasks.md T077, User Story 4 scenarios
 * 1, 2, 3 and 5, quickstart.md scenarios 9, 10 and 12).
 *
 * <p>**Validates: Requirements FR-025, FR-026, SC-003**</p>
 */
class ConcurrentUnitsOfWorkTest {

    private static final String TICKET = "ticket-1";
    private static final long TIMEOUT_SECONDS = 10;
    private static final long DWELL_MILLIS = 2;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static String open(SagaRuntime engine, String ticketId) {
        return onlyResult(engine.receive(new TicketOpened(ticketId))).sagaId();
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    /** Runs both calls on two threads released together; returns their reports in call order. */
    private List<HandlingReport> concurrently(Callable<HandlingReport> first, Callable<HandlingReport> second)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<HandlingReport>> futures = new ArrayList<>();
        for (Callable<HandlingReport> call : List.of(first, second)) {
            futures.add(executor.submit(() -> {
                barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return call.call();
            }));
        }
        List<HandlingReport> reports = new ArrayList<>();
        for (Future<HandlingReport> future : futures) {
            reports.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        return reports;
    }

    /** Both signals ran one after the other: no overlap, both recorded, the second saw the first's value. */
    private static void assertSerialized(SignalWorld world, String sagaId, List<HandlingReport> reports) {
        assertThat(reports).allSatisfy(report ->
                assertThat(onlyResult(report).outcome()).isEqualTo(HandlingOutcome.WAITING));
        assertThat(world.probe().maxOverlap(sagaId)).isEqualTo(1);

        SagaInstance instance = world.committed(sagaId);
        assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(instance.steps().get(SignalSaga.SIGNAL_A_STEP).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(instance.steps().get(SignalSaga.SIGNAL_B_STEP).status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(world.history(sagaId)).filteredOn(entry -> entry.kind() == SagaLifecycleEventKind.STEP_COMPLETED)
                .extracting(SagaLifecycleEntry::stepName)
                .containsExactlyInAnyOrder(SignalSaga.START_STEP, SignalSaga.SIGNAL_A_STEP, SignalSaga.SIGNAL_B_STEP);
        assertThat(world.history(sagaId)).noneMatch(entry -> entry.kind() == SagaLifecycleEventKind.FAILED);

        List<String> signalsInOrder = world.probe().enteredSteps().stream()
                .filter(step -> !step.equals(SignalSaga.START_STEP)).toList();
        assertThat(signalsInOrder).hasSize(2);
        assertThat(world.probe().seenBy(signalsInOrder.get(0))).isEmpty();
        assertThat(world.probe().seenBy(signalsInOrder.get(1))).contains(signalsInOrder.get(0));
    }

    @RepeatedTest(100)
    @DisplayName("US4-1, US4-3: two engines, two signals for one waiting instance never overlap")
    void twoEnginesSerialize() throws Exception {
        SignalWorld world = new SignalWorld();
        world.probe().dwell(DWELL_MILLIS);
        SagaRuntime first = world.engine();
        SagaRuntime second = world.engine();
        String sagaId = open(first, TICKET);

        List<HandlingReport> reports = concurrently(() -> first.receive(new SignalA(TICKET)),
                () -> second.receive(new SignalB(TICKET)));

        assertSerialized(world, sagaId, reports);
    }

    @RepeatedTest(100)
    @DisplayName("US4-1: one engine, two threads, two signals for one waiting instance never overlap")
    void oneEngineSerializes() throws Exception {
        SignalWorld world = new SignalWorld();
        world.probe().dwell(DWELL_MILLIS);
        SagaRuntime engine = world.engine();
        String sagaId = open(engine, TICKET);

        List<HandlingReport> reports = concurrently(() -> engine.receive(new SignalA(TICKET)),
                () -> engine.receive(new SignalB(TICKET)));

        assertSerialized(world, sagaId, reports);
    }

    @Test
    @DisplayName("US4-2: an event waiting while the first unit of work completes the saga is UNEXPECTED")
    void secondEventAfterCompletionIsUnexpected() throws Exception {
        SignalWorld world = new SignalWorld();
        SagaRuntime first = world.engine();
        SagaRuntime second = world.engine();
        String sagaId = open(first, TICKET);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        world.probe().hold(sagaId, SignalSaga.END_STEP, closing, release);

        Future<HandlingReport> close = executor.submit(() -> first.receive(new TicketClosed(TICKET)));
        assertThat(closing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<HandlingReport> signal = executor.submit(() -> second.receive(new SignalA(TICKET)));
        release.countDown();

        assertThat(onlyResult(close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).outcome())
                .isEqualTo(HandlingOutcome.COMPLETED);
        SagaHandlingResult late = onlyResult(signal.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(late.outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
        assertThat(late.sagaId()).isEqualTo(sagaId);
        assertThat(world.committed(sagaId).steps().get(SignalSaga.SIGNAL_A_STEP).status())
                .isEqualTo(StepStatus.NOT_RUN);
    }

    @Test
    @DisplayName("US4-5: an instance held in a step does not keep another instance's unit of work waiting")
    void differentInstancesDoNotWait() throws Exception {
        SignalWorld world = new SignalWorld();
        SagaRuntime engine = world.engine();
        String held = open(engine, "ticket-held");
        String free = open(engine, "ticket-free");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        world.probe().hold(held, SignalSaga.SIGNAL_A_STEP, entered, release);

        Future<HandlingReport> blocked = executor.submit(() -> engine.receive(new SignalA("ticket-held")));
        assertThat(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<HandlingReport> other = executor.submit(() -> engine.receive(new SignalA("ticket-free")));

        try {
            assertThat(onlyResult(other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).outcome())
                    .isEqualTo(HandlingOutcome.WAITING);
            assertThat(blocked).isNotDone();
            assertThat(world.committed(free).steps().get(SignalSaga.SIGNAL_A_STEP).status())
                    .isEqualTo(StepStatus.COMPLETED);
        } finally {
            release.countDown();
        }
        assertThat(onlyResult(blocked.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).outcome())
                .isEqualTo(HandlingOutcome.WAITING);
        assertThat(world.committed(held).steps().get(SignalSaga.SIGNAL_A_STEP).status())
                .isEqualTo(StepStatus.COMPLETED);
    }
}
