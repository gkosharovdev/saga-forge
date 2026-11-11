package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Finished;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindSaga;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 5: compensation survives a crash (specs/006-saga-compensation spec.md US5 scenario 1, FR-004, FR-012,
 * research §10; tasks.md T054). A crash is a {@link CompensationFaults.Crash} out of the store; the engine is then
 * rebuilt over the same stores and the event redelivered.
 */
class CompensationResumeTest {

    private static List<String> compensations(UnwindFixture fixture) {
        return fixture.journal().compensations().stream().filter(name -> name.startsWith("undo")).toList();
    }

    private static UnwindFixture threeCommitted() {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        fixture.start("o-1");
        fixture.receive(new BDone("o-1"));
        fixture.receive(new CDone("o-1"));
        return fixture;
    }

    /** Aborts with a crash at the lock of B's compensation: after C's compensation committed, before B's ran. */
    private static String crashAfterFirstCompensation(UnwindFixture fixture) {
        CompensationFaults faults = fixture.withFaults();
        // Lock 1: the abort's unit of work; lock 2: C's compensation; lock 3: B's compensation.
        faults.crashOnLock(3);
        Throwable crashed = catchThrowable(() -> fixture.receive(new Aborted("o-1")));
        assertThat(crashed).isNotNull();
        String sagaId = "saga-1";
        SagaInstance afterCrash = fixture.committed(sagaId);
        assertThat(afterCrash.status()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(afterCrash.steps().get(UnwindSaga.C).compensation().status())
                .isEqualTo(CompensationStatus.COMPENSATED);
        assertThat(afterCrash.steps().get(UnwindSaga.B).compensation().status())
                .isEqualTo(CompensationStatus.NOT_ATTEMPTED);
        assertThat(compensations(fixture)).containsExactly("undoC");
        return sagaId;
    }

    @Test
    @DisplayName("5.1 C compensated before the crash: the redelivered trigger compensates B and A, C only once")
    void redeliveredTriggerResumes() {
        UnwindFixture fixture = threeCommitted();
        String sagaId = crashAfterFirstCompensation(fixture);

        SagaHandlingResult resumed = fixture.restart().receive(new Aborted("o-1"));

        assertThat(resumed.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(fixture.logger().byEvent("saga.compensation.resumed")).hasSize(1);
    }

    @Test
    @DisplayName("a forward event for a compensating instance resumes compensation and never runs its step")
    void forwardEventResumes() {
        UnwindFixture fixture = threeCommitted();
        String sagaId = crashAfterFirstCompensation(fixture);

        SagaHandlingResult resumed = fixture.restart().receive(new Finished("o-1"));

        assertThat(resumed.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(resumed.outcomeStep()).isNull();
        SagaInstance instance = fixture.committed(sagaId);
        assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(instance.outcome()).isNull();
        assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
    }

    @Test
    @DisplayName("after COMPENSATED a forward event is UNEXPECTED and the trigger DUPLICATE: nothing runs again")
    void afterTheEndNothingResumes() {
        UnwindFixture fixture = threeCommitted();
        crashAfterFirstCompensation(fixture);
        fixture.restart().receive(new Aborted("o-1"));

        assertThat(fixture.receive(new Finished("o-1")).outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
        assertThat(fixture.receive(new Aborted("o-1")).outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
    }

    @Test
    @DisplayName("a crash before the start of compensation committed leaves nothing to resume: the redelivery starts it")
    void crashBeforeStartCommitted() {
        UnwindFixture fixture = threeCommitted();
        CompensationFaults faults = fixture.withFaults();
        faults.crashOnSave(1);
        assertThat(catchThrowable(() -> fixture.receive(new Aborted("o-1")))).isNotNull();
        assertThat(fixture.committed("saga-1").status()).isEqualTo(SagaStatus.RUNNING);

        SagaHandlingResult retried = fixture.restart().receive(new Aborted("o-1"));

        assertThat(retried.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
    }

    @Test
    @DisplayName("two triggers delivered at once start compensation once (FR-004)")
    void concurrentTriggersCompensateOnce() throws Exception {
        UnwindFixture fixture = threeCommitted();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<HandlingOutcome>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(threads.submit(() -> {
                    go.await();
                    return fixture.receive(new Aborted("o-1")).outcome();
                }));
            }
            go.countDown();
            List<HandlingOutcome> results = new ArrayList<>();
            for (Future<HandlingOutcome> outcome : outcomes) {
                results.add(outcome.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).containsExactlyInAnyOrder(HandlingOutcome.COMPENSATED, HandlingOutcome.DUPLICATE);
            assertThat(compensations(fixture)).containsExactly("undoC", "undoB", "undoA");
        } finally {
            threads.shutdownNow();
        }
    }
}
