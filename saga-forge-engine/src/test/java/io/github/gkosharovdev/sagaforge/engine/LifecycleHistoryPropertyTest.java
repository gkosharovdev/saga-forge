package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.engine.fixtures.failure.FailureFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.failure.FailureSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.failure.FaultPoint;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider.StoredEntry;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 27: the stored lifecycle history is the true order of what happened, and a rolled-back unit of work leaves
 * no entry of its own.
 *
 * <p>**Validates: Requirements FR-033, SC-010**</p>
 */
class LifecycleHistoryPropertyTest {

    /** A {@code STEP_COMPLETED} or {@code COMPLETED} entry as (kind, step or outcome). */
    private record Shape(SagaLifecycleEventKind kind, String step) {

        static Shape of(SagaLifecycleEntry entry) {
            return new Shape(entry.kind(),
                    entry.kind() == SagaLifecycleEventKind.COMPLETED ? entry.outcomeStep() : entry.stepName());
        }
    }

    private static final List<Shape> COMPLETED_HISTORY = List.of(
            new Shape(SagaLifecycleEventKind.CREATED, null),
            new Shape(SagaLifecycleEventKind.STEP_COMPLETED, FailureSaga.START_STEP),
            new Shape(SagaLifecycleEventKind.STEP_COMPLETED, FailureSaga.FEE_STEP),
            new Shape(SagaLifecycleEventKind.STEP_COMPLETED, FailureSaga.END_STEP),
            new Shape(SagaLifecycleEventKind.COMPLETED, FailureSaga.END_STEP));

    private static final List<Shape> FAILED_HISTORY = List.of(
            new Shape(SagaLifecycleEventKind.CREATED, null),
            new Shape(SagaLifecycleEventKind.FAILED, null));

    @Provide
    Arbitrary<Optional<FaultPoint>> faults() {
        return Arbitraries.of(FaultPoint.class).optional();
    }

    @Property(tries = 200)
    void historyIsCreatedThenCompletedStepsOrFailed(@ForAll("faults") Optional<FaultPoint> fault) {
        FailureFixture fixture = new FailureFixture();
        fault.ifPresent(fixture.plan()::arm);

        String sagaId = fixture.requestParcel("parcel-1", 4).results().get(0).sagaId();

        List<StoredEntry> history = fixture.storage().history(sagaId);
        List<Shape> shapes = history.stream().map(StoredEntry::entry).map(Shape::of).toList();
        assertThat(shapes).isEqualTo(fault.isPresent() ? FAILED_HISTORY : COMPLETED_HISTORY);
        assertThat(history).extracting(StoredEntry::sequenceNumber)
                .containsExactlyElementsOf(LongStream.range(0, history.size()).boxed().toList());
        assertThat(history).allSatisfy(stored -> assertThat(stored.entry().sagaId()).isEqualTo(sagaId));
    }
}
