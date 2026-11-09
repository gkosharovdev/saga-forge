package io.github.gkosharovdev.sagaforge.engine.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code StepRecord.completionSequence} as {@link EngineSagaLifecycle} assigns it (specs/006-saga-compensation
 * research §5, tasks.md T014): consecutive in execution order within a unit of work, continued by later ones, and
 * never assigned by a unit of work whose write is discarded.
 */
class CompletionSequenceTest {

    private final InMemoryStorageProvider storage = new InMemoryStorageProvider();
    private final TimeAndIdentity sources = new TimeAndIdentity(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), () -> "saga-1", () -> "uow");
    private final SagaModel model = SagaModel.of(ThreeStepSaga.class);

    private SagaInstance created() {
        EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(model, storage, sources);
        lifecycle.start(ThreeStepSaga.class, "orderId", "order-1");
        return lifecycle.drain("saga-1").instance();
    }

    /** One unit of work over {@code from}: completes {@code steps} in order and returns the working copy. */
    private SagaInstance unitOfWork(SagaInstance from, String... steps) {
        EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(model, storage, sources);
        lifecycle.load(from);
        for (String step : steps) {
            lifecycle.recordStepCompleted("saga-1", step, List.of(), 1, List.of());
        }
        lifecycle.advance("saga-1");
        return lifecycle.drain("saga-1").instance();
    }

    private static int sequence(SagaInstance instance, String step) {
        StepRecord record = instance.steps().get(step);
        return record.completionSequence();
    }

    @Test
    @DisplayName("steps completed in one unit of work get consecutive sequences in execution order")
    void consecutiveWithinUnitOfWork() {
        SagaInstance after = unitOfWork(created(), ThreeStepSaga.START_STEP, ThreeStepSaga.RESERVED_STEP);

        assertThat(sequence(after, ThreeStepSaga.START_STEP)).isEqualTo(1);
        assertThat(sequence(after, ThreeStepSaga.RESERVED_STEP)).isEqualTo(2);
        assertThat(sequence(after, ThreeStepSaga.INVOICED_STEP)).isZero();
    }

    @Test
    @DisplayName("a later unit of work continues from the highest sequence")
    void laterUnitOfWorkContinues() {
        SagaInstance first = unitOfWork(created(), ThreeStepSaga.START_STEP);
        SagaInstance second = unitOfWork(first, ThreeStepSaga.INVOICED_STEP, ThreeStepSaga.RESERVED_STEP);

        assertThat(sequence(second, ThreeStepSaga.START_STEP)).isEqualTo(1);
        assertThat(sequence(second, ThreeStepSaga.INVOICED_STEP)).isEqualTo(2);
        assertThat(sequence(second, ThreeStepSaga.RESERVED_STEP)).isEqualTo(3);
    }

    @Test
    @DisplayName("a discarded unit of work assigns nothing: the next one starts from the stored instance")
    void discardedUnitOfWorkAssignsNothing() {
        SagaInstance first = unitOfWork(created(), ThreeStepSaga.START_STEP);
        unitOfWork(first, ThreeStepSaga.RESERVED_STEP); // rolled back: its working copy is never stored
        SagaInstance retried = unitOfWork(first, ThreeStepSaga.INVOICED_STEP);

        assertThat(sequence(retried, ThreeStepSaga.INVOICED_STEP)).isEqualTo(2);
        assertThat(sequence(retried, ThreeStepSaga.RESERVED_STEP)).isZero();
    }
}
