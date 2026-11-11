package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Finished;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindSaga;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property 34: each compensation at most once, none skipped (specs/006-saga-compensation FR-004, FR-012, SC-003;
 * tasks.md T055). A run over A, B, C committed crashes at a random lock or save, possibly again after each restart;
 * the instance is resumed by random later events (the trigger again, or a forward event) until it ends. Exactly one
 * {@code STEP_COMPENSATED} per step is committed; a compensation rolled back by a crash may be invoked again.
 */
class CompensationResumePropertyTest {

    /** A crash point: at the {@code n}-th lock or save after arming. */
    record CrashPoint(boolean atLock, int n) {
    }

    @Property(tries = 150)
    void eachCompensationExactlyOnce(@ForAll("crashSchedules") List<CrashPoint> crashes,
            @ForAll @IntRange(min = 0, max = 1) int resumeWith) {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        String sagaId = fixture.start("o-1");
        fixture.receive(new BDone("o-1"));
        fixture.receive(new CDone("o-1"));
        Object next = new Aborted("o-1");
        for (CrashPoint crash : crashes) {
            CompensationFaults faults = fixture.withFaults();
            if (crash.atLock()) {
                faults.crashOnLock(crash.n());
            } else {
                faults.crashOnSave(crash.n());
            }
            Object event = next;
            catchThrowable(() -> fixture.receive(event));
            fixture.restart();
            if (fixture.committed(sagaId).status() == SagaStatus.COMPENSATING) {
                next = resumeWith == 0 ? new Aborted("o-1") : new Finished("o-1");
            }
        }
        fixture.receive(next);

        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(CompensationFaultInjectionTest.committedCompensations(fixture, sagaId))
                .containsExactly(UnwindSaga.C, UnwindSaga.B, UnwindSaga.A);
        assertThat(CompensationFaultInjectionTest.invokedInOrder(fixture)).containsExactly("undoC", "undoB", "undoA");
    }

    @Provide
    Arbitrary<List<CrashPoint>> crashSchedules() {
        Arbitrary<CrashPoint> point = Arbitraries.integers().between(1, 5).flatMap(n ->
                Arbitraries.of(true, false).map(atLock -> new CrashPoint(atLock, n)));
        return point.list().ofMinSize(1).ofMaxSize(3);
    }
}
