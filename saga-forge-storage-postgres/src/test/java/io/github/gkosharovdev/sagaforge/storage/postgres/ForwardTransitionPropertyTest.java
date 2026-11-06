package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.statistics.Statistics;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Properties of {@link LifecycleHistoryDerivation#requireForwardTransition} (contracts/concurrency-control.md
 * "Forward transition") and of idempotent re-save. Pure: no database, so it does not touch
 * {@link PostgresTestSupport}.
 *
 * <p>Instances use a fixed set of step names, each absent, {@code NOT_RUN}, or {@code COMPLETED} with a small
 * {@code touchedAggregates} list, and every {@link SagaStatus}, with a fixed {@code outcome} and {@code failure}
 * per terminal status. The incoming snapshot keeps each previous step and the previous status with some
 * probability, so both accepted and rejected pairs are common.</p>
 */
class ForwardTransitionPropertyTest {

    private static final String SAGA_ID = "s-1";
    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final List<String> STEP_NAMES = List.of("A", "B", "C", "D");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant MODIFIED_AT = CREATED_AT.plusSeconds(60);
    private static final SagaFailure FAILURE =
            new SagaFailure("B", "java.lang.IllegalStateException", "boom", MODIFIED_AT);
    private static final List<AggregateReference> AGGREGATES = List.of(
            new AggregateReference("com.example.Order", "42"),
            new AggregateReference("com.example.Payment", "p-7"),
            new AggregateReference("com.example.Stock", "s-3"));

    /**
     * Forward transition: accepts exactly when every previously {@code COMPLETED} step is still
     * {@code COMPLETED} with equal {@code touchedAggregates} and a terminal previous status is unchanged;
     * otherwise throws {@link ConcurrentSagaUpdateException} for the saga.
     *
     * <p><b>Validates: FR-010, SC-004</b></p>
     */
    @Property(tries = 1000)
    void acceptsExactlyForwardTransitions(@ForAll("transitions") Tuple2<SagaInstance, SagaInstance> transition) {
        SagaInstance previous = transition.get1();
        SagaInstance incoming = transition.get2();
        boolean expected = isForwardTransition(previous, incoming);
        Statistics.collect(expected ? "forward" : "violation");

        if (expected) {
            assertThatCode(() -> LifecycleHistoryDerivation.requireForwardTransition(previous, incoming))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> LifecycleHistoryDerivation.requireForwardTransition(previous, incoming))
                    .isInstanceOf(ConcurrentSagaUpdateException.class)
                    .satisfies(e -> assertThat(((ConcurrentSagaUpdateException) e).sagaId()).isEqualTo(SAGA_ID));
        }
    }

    /**
     * Idempotent re-save: any instance is a forward transition from itself and derives no history entry.
     *
     * <p><b>Validates: FR-009, FR-010</b></p>
     */
    @Property(tries = 500)
    void reSavingTheSameSnapshotIsAcceptedAndDerivesNothing(@ForAll("instances") SagaInstance instance,
                                                           @ForAll @LongRange(min = 0, max = 10_000) long next) {
        assertThatCode(() -> LifecycleHistoryDerivation.requireForwardTransition(instance, instance))
                .doesNotThrowAnyException();
        assertThat(LifecycleHistoryDerivation.derive(instance, instance, next)).isEmpty();
    }

    /** The oracle: contracts/concurrency-control.md "Forward transition", restated independently. */
    private static boolean isForwardTransition(SagaInstance previous, SagaInstance incoming) {
        for (Map.Entry<String, StepRecord> step : previous.steps().entrySet()) {
            if (step.getValue().status() != StepStatus.COMPLETED) {
                continue;
            }
            StepRecord now = incoming.steps().get(step.getKey());
            if (now == null || now.status() != StepStatus.COMPLETED
                    || !now.touchedAggregates().equals(step.getValue().touchedAggregates())) {
                return false;
            }
        }
        boolean terminal = previous.status() == SagaStatus.COMPLETED || previous.status() == SagaStatus.FAILED;
        return !terminal || incoming.status() == previous.status();
    }

    @Provide
    Arbitrary<SagaInstance> instances() {
        return Combinators.combine(stepMaps(), Arbitraries.of(SagaStatus.class))
                .as(ForwardTransitionPropertyTest::instance);
    }

    @Provide
    Arbitrary<Tuple2<SagaInstance, SagaInstance>> transitions() {
        return instances().flatMap(previous -> Combinators.combine(
                        incomingSteps(previous.steps()),
                        Arbitraries.frequencyOf(
                                Tuple.of(1, Arbitraries.just(previous.status())),
                                Tuple.of(1, Arbitraries.of(SagaStatus.class))))
                .as((steps, status) -> Tuple.of(previous, instance(steps, status))));
    }

    /** Each step independently keeps its previous record (or absence) half the time, else is redrawn. */
    private static Arbitrary<Map<String, StepRecord>> incomingSteps(Map<String, StepRecord> previous) {
        Arbitrary<Map<String, StepRecord>> result = Arbitraries.just(Map.of());
        for (String name : STEP_NAMES) {
            Arbitrary<Optional<StepRecord>> kept = Arbitraries.just(Optional.ofNullable(previous.get(name)));
            Arbitrary<Optional<StepRecord>> step =
                    Arbitraries.frequencyOf(Tuple.of(1, kept), Tuple.of(1, stepRecord()));
            result = Combinators.combine(result, step).as((steps, record) -> with(steps, name, record));
        }
        return result;
    }

    private static Arbitrary<Map<String, StepRecord>> stepMaps() {
        Arbitrary<Map<String, StepRecord>> result = Arbitraries.just(Map.of());
        for (String name : STEP_NAMES) {
            result = Combinators.combine(result, stepRecord()).as((steps, record) -> with(steps, name, record));
        }
        return result;
    }

    /** Absent, {@code NOT_RUN}, or {@code COMPLETED} with zero to two of the aggregates, in drawn order. */
    private static Arbitrary<Optional<StepRecord>> stepRecord() {
        Arbitrary<Optional<StepRecord>> completed = Arbitraries.of(AGGREGATES).list().uniqueElements().ofMaxSize(2)
                .map(touched -> Optional.of(StepRecord.completed(touched)));
        Arbitrary<Optional<StepRecord>> absent = Arbitraries.just(Optional.empty());
        Arbitrary<Optional<StepRecord>> notRun = Arbitraries.just(Optional.of(StepRecord.notRun()));
        return Arbitraries.oneOf(absent, notRun, completed);
    }

    private static Map<String, StepRecord> with(Map<String, StepRecord> steps, String name,
                                                Optional<StepRecord> record) {
        Map<String, StepRecord> copy = new HashMap<>(steps);
        record.ifPresent(r -> copy.put(name, r));
        return Map.copyOf(copy);
    }

    private static SagaInstance instance(Map<String, StepRecord> steps, SagaStatus status) {
        return new SagaInstance(SAGA_ID, SAGA_TYPE, Map.of(), Map.of("orderId", "o-1"), status, CREATED_AT,
                MODIFIED_AT, steps, status == SagaStatus.COMPLETED ? "A" : null,
                status == SagaStatus.FAILED ? FAILURE : null);
    }
}
