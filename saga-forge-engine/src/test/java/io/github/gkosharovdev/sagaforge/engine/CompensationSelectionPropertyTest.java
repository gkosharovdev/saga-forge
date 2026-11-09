package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.DTried;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.compensation.UnwindSaga;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 33: compensation selection and order (specs/006-saga-compensation FR-005, FR-007, SC-002; tasks.md T035).
 *
 * <p>For random sequences of units of work over B, C and D that commit or roll back (a write-time conflict), then a
 * business failure: the compensations run are exactly those of the committed steps, the start step A included, in
 * reverse commit order; a step without a compensation is {@code NOTHING_TO_COMPENSATE}; a rolled-back step has no
 * compensation state.</p>
 */
class CompensationSelectionPropertyTest {

    /** One unit of work: the step it runs and whether its write loses a conflict (rolls back). */
    record Delivery(String step, boolean rolledBack) {
    }

    private static final Map<String, Object> EVENTS = Map.of(
            UnwindSaga.B, new BDone("o-1"), UnwindSaga.C, new CDone("o-1"), UnwindSaga.D, new DTried("o-1"));

    @Property(tries = 150)
    void compensationSelectionAndOrder(@ForAll("deliveries") List<Delivery> deliveries,
            @ForAll boolean compensationForB) {
        UnwindFixture fixture = compensationForB ? UnwindFixture.withEveryCompensation()
                : UnwindFixture.withoutCompensationForB();
        String sagaId = fixture.start("o-1");
        List<String> committed = new ArrayList<>(List.of(UnwindSaga.A));
        for (Delivery delivery : deliveries) {
            boolean repeated = committed.contains(delivery.step());
            if (delivery.rolledBack() && !repeated) {
                // A repeated event is DUPLICATE and writes nothing, so a conflict would stay armed for the next write.
                fixture.storage().conflictOnNextSave(sagaId);
            }
            HandlingOutcome outcome = fixture.receive(EVENTS.get(delivery.step())).outcome();
            if (outcome == HandlingOutcome.WAITING) {
                committed.add(delivery.step());
            } else {
                assertThat(outcome).isIn(HandlingOutcome.NOT_HANDLED, HandlingOutcome.DUPLICATE);
            }
        }

        assertThat(fixture.receive(new Aborted("o-1")).outcome()).isEqualTo(HandlingOutcome.COMPENSATED);

        List<String> expected = new ArrayList<>();
        for (int i = committed.size() - 1; i >= 0; i--) {
            String step = committed.get(i);
            if (compensationForB || !step.equals(UnwindSaga.B)) {
                expected.add("undo" + step.toUpperCase());
            }
        }
        List<String> ran = fixture.journal().compensations().stream().filter(name -> name.startsWith("undo"))
                .toList();
        assertThat(ran).containsExactlyElementsOf(expected);

        SagaInstance instance = fixture.committed(sagaId);
        for (String step : List.of(UnwindSaga.B, UnwindSaga.C, UnwindSaga.D)) {
            if (instance.steps().get(step).status() != StepStatus.COMPLETED) {
                assertThat(instance.steps().get(step).compensation()).isNull();
            } else if (step.equals(UnwindSaga.B) && !compensationForB) {
                assertThat(instance.steps().get(step).compensation().status())
                        .isEqualTo(CompensationStatus.NOTHING_TO_COMPENSATE);
            } else {
                assertThat(instance.steps().get(step).compensation().status())
                        .isEqualTo(CompensationStatus.COMPENSATED);
            }
        }
    }

    @Provide
    Arbitrary<List<Delivery>> deliveries() {
        Arbitrary<Delivery> delivery = Combinators.combine(
                        Arbitraries.of(UnwindSaga.B, UnwindSaga.C, UnwindSaga.D), Arbitraries.of(true, false))
                .as(Delivery::new);
        return delivery.list().ofMaxSize(6);
    }
}
