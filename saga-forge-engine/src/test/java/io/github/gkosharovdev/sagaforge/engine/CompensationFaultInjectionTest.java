package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.DeliveryFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.UnwindSaga;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Crashes at every storage call of a compensation run, then a restart and a redelivery (specs/006-saga-compensation
 * SC-002, SC-003, FR-012; tasks.md T055): every committed step with a compensation is compensated exactly once, in
 * reverse completion order, and none is skipped. "Compensated" means committed: a compensation whose own unit of work
 * a crash rolled back runs again on resume (its method is invoked twice, with the same command identities), but
 * exactly one {@code STEP_COMPENSATED} per step is ever committed. Rolled-back steps at every step boundary are covered by
 * {@code CompensationSelectionPropertyTest}.
 */
class CompensationFaultInjectionTest {

    /**
     * Over A, B, C committed, the abort's run makes 4 locks (the abort's unit of work, then C, B, A) and 4 saves; a
     * crash point past those runs everything without crashing.
     */
    static Stream<Arguments> crashPoints() {
        List<Arguments> points = new ArrayList<>();
        for (int n = 1; n <= 5; n++) {
            points.add(Arguments.of("lock", n));
            points.add(Arguments.of("save", n));
        }
        return points.stream();
    }

    /** The steps whose compensation committed, in commit order. */
    static List<String> committedCompensations(UnwindFixture fixture, String sagaId) {
        return fixture.storage().history(sagaId).stream().map(stored -> stored.entry())
                .filter(entry -> entry.kind() == SagaLifecycleEventKind.STEP_COMPENSATED)
                .map(entry -> entry.stepName()).toList();
    }

    /** The compensations invoked, with immediate repeats (re-runs of a rolled-back one) collapsed. */
    static List<String> invokedInOrder(UnwindFixture fixture) {
        List<String> collapsed = new ArrayList<>();
        for (String name : fixture.journal().compensations()) {
            if (name.startsWith("undo") && (collapsed.isEmpty() || !collapsed.get(collapsed.size() - 1).equals(name))) {
                collapsed.add(name);
            }
        }
        return collapsed;
    }

    @ParameterizedTest(name = "crash at {0} #{1}")
    @MethodSource("crashPoints")
    @DisplayName("a crash anywhere in the run, then a redelivery: C, B, A each compensated exactly once")
    void crashThenResume(String where, int n) {
        UnwindFixture fixture = UnwindFixture.withEveryCompensation();
        String sagaId = fixture.start("o-1");
        fixture.receive(new BDone("o-1"));
        fixture.receive(new CDone("o-1"));
        CompensationFaults faults = fixture.withFaults();
        if (where.equals("lock")) {
            faults.crashOnLock(n);
        } else {
            faults.crashOnSave(n);
        }

        Throwable crashed = catchThrowable(() -> fixture.receive(new Aborted("o-1")));
        HandlingOutcome finalOutcome = crashed == null ? HandlingOutcome.COMPENSATED
                : fixture.restart().receive(new Aborted("o-1")).outcome();

        assertThat(finalOutcome).isIn(HandlingOutcome.COMPENSATED, HandlingOutcome.DUPLICATE);
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(committedCompensations(fixture, sagaId)).as("committed after a crash at %s #%d", where, n)
                .containsExactly(UnwindSaga.C, UnwindSaga.B, UnwindSaga.A);
        assertThat(invokedInOrder(fixture)).containsExactly("undoC", "undoB", "undoA");
    }

    @Test
    @DisplayName("a refund interrupted before its commit re-runs with the same CommandId: refunded once")
    void interruptedRefundKeepsItsIdentity() {
        DeliveryFixture fixture = new DeliveryFixture();
        String sagaId = fixture.placedAndCharged("o-1");
        // Save 1: the rejection's unit of work; save 2: the refund's unit of work, after the refund was handled.
        fixture.withFaults().crashOnSave(2);

        assertThat(catchThrowable(() -> fixture.reject("o-1", "no capacity"))).isNotNull();
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATING);
        SagaStatus after = fixture.restart().reject("o-1", "no capacity").outcome() == HandlingOutcome.COMPENSATED
                ? fixture.committed(sagaId).status() : null;

        assertThat(after).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(fixture.payments().refundIds()).hasSize(2);
        assertThat(fixture.payments().refundIds().get(1)).isEqualTo(fixture.payments().refundIds().get(0));
        assertThat(fixture.payments().refunds()).hasSize(1);
    }
}
