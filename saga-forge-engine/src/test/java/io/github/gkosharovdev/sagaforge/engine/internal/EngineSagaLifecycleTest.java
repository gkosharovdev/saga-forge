package io.github.gkosharovdev.sagaforge.engine.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Reservation;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.InvalidSagaTransitionException;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link EngineSagaLifecycle}: the 001/002 transition rules applied to a working copy, the lifecycle entries each
 * transition produces, and {@code drain} (specs/004-saga-engine tasks.md T043, data-model.md "Lifecycle entry
 * production").
 */
class EngineSagaLifecycleTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ORDER = "order-1";
    private static final AggregateReference RESERVATION = AggregateReference.of(Reservation.class, "reservation-1");

    /** A clock one second further on every read, so each transition has its own instant. */
    private static final class TickingClock extends Clock {

        private final AtomicInteger ticks = new AtomicInteger();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return START.plus(Duration.ofSeconds(ticks.getAndIncrement()));
        }
    }

    private final InMemoryStorageProvider storage = new InMemoryStorageProvider();
    private final AtomicInteger sagaIds = new AtomicInteger();
    private final TimeAndIdentity sources = new TimeAndIdentity(new TickingClock(),
            () -> "saga-" + sagaIds.incrementAndGet(), () -> "uow");
    private final SagaModel threeStep = SagaModel.of(ThreeStepSaga.class);
    private final EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(threeStep, storage, sources);

    private SagaInstance started() {
        return lifecycle.start(ThreeStepSaga.class, "orderId", ORDER);
    }

    private static SagaInstance stored(String sagaId, Class<?> sagaType, SagaStatus status) {
        String outcome = status == SagaStatus.COMPLETED ? ThreeStepSaga.END_STEP : null;
        SagaFailure failure = status == SagaStatus.FAILED
                ? SagaFailure.of(ThreeStepSaga.START_STEP, new IllegalStateException("earlier failure"), START)
                : null;
        return new SagaInstance(sagaId, sagaType.getName(), Map.of(), Map.of("orderId", ORDER), status, START, START,
                Map.of(), outcome, failure);
    }

    private static List<SagaLifecycleEventKind> kinds(PendingWrite write) {
        return write.entries().stream().map(SagaLifecycleEntry::kind).toList();
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("builds a CREATED instance with a fresh id, every step NOT_RUN and empty state, writing nothing")
        void buildsCreatedInstance() {
            SagaInstance instance = started();

            assertThat(instance.sagaId()).isEqualTo("saga-1");
            assertThat(instance.sagaType()).isEqualTo(ThreeStepSaga.class.getName());
            assertThat(instance.status()).isEqualTo(SagaStatus.CREATED);
            assertThat(instance.associations()).isEqualTo(Map.of("orderId", ORDER));
            assertThat(instance.state()).isEmpty();
            assertThat(instance.createdAt()).isEqualTo(instance.lastModifiedAt());
            assertThat(instance.steps()).containsOnlyKeys(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
            assertThat(instance.steps().values()).containsOnly(StepRecord.notRun());
            assertThat(instance.outcome()).isNull();
            assertThat(instance.failure()).isNull();
            assertThat(storage.all()).isEmpty();
        }

        @Test
        @DisplayName("produces one CREATED entry at createdAt")
        void producesCreatedEntry() {
            SagaInstance instance = started();

            PendingWrite write = lifecycle.drain(instance.sagaId());

            assertThat(write.instance()).isEqualTo(instance);
            assertThat(write.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.sagaId()).isEqualTo(instance.sagaId());
                assertThat(entry.kind()).isEqualTo(SagaLifecycleEventKind.CREATED);
                assertThat(entry.occurredAt()).isEqualTo(instance.createdAt());
            });
        }

        @Test
        @DisplayName("an active instance of the same saga type and value is a duplicate")
        void activeInstanceIsDuplicate() {
            storage.save(stored("existing", ThreeStepSaga.class, SagaStatus.RUNNING), List.of());

            assertThatThrownBy(EngineSagaLifecycleTest.this::started)
                    .isInstanceOf(DuplicateSagaInstanceException.class);
        }

        @Test
        @DisplayName("a terminal instance, or an active one of another saga type, is not a duplicate")
        void terminalOrOtherTypeIsNotDuplicate() {
            storage.save(stored("done", ThreeStepSaga.class, SagaStatus.COMPLETED), List.of());
            storage.save(stored("failed", ThreeStepSaga.class, SagaStatus.FAILED), List.of());
            storage.save(stored("other", CreateBookingSaga.class, SagaStatus.RUNNING), List.of());

            assertThat(started().status()).isEqualTo(SagaStatus.CREATED);
        }

        @Test
        @DisplayName("a second start on the same lifecycle, or one for another saga type, is rejected")
        void secondStartRejected() {
            started();

            assertThatThrownBy(EngineSagaLifecycleTest.this::started).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new EngineSagaLifecycle(threeStep, storage, sources)
                    .start(CreateBookingSaga.class, "bookingId", "b-1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        @DisplayName("recordStepCompleted marks the step COMPLETED with its aggregates and adds STEP_COMPLETED")
        void recordStepCompleted() {
            String sagaId = started().sagaId();
            lifecycle.drain(sagaId);

            SagaInstance instance = lifecycle.recordStepCompleted(sagaId, ThreeStepSaga.START_STEP,
                    List.of(RESERVATION));

            assertThat(instance.steps().get(ThreeStepSaga.START_STEP))
                    .isEqualTo(StepRecord.completed(List.of(RESERVATION)));
            assertThat(instance.steps().get(ThreeStepSaga.RESERVED_STEP).status()).isEqualTo(StepStatus.NOT_RUN);
            PendingWrite write = lifecycle.drain(sagaId);
            assertThat(write.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(SagaLifecycleEventKind.STEP_COMPLETED);
                assertThat(entry.stepName()).isEqualTo(ThreeStepSaga.START_STEP);
                assertThat(entry.touchedAggregates()).containsExactly(RESERVATION);
                assertThat(entry.occurredAt()).isEqualTo(instance.lastModifiedAt());
            });
        }

        @Test
        @DisplayName("an unknown step name is rejected")
        void unknownStepRejected() {
            String sagaId = started().sagaId();

            assertThatThrownBy(() -> lifecycle.recordStepCompleted(sagaId, "noSuchStep", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("advance makes the instance RUNNING and adds no entry")
        void advanceAddsNoEntry() {
            String sagaId = started().sagaId();
            lifecycle.drain(sagaId);

            SagaInstance instance = lifecycle.advance(sagaId);

            assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(lifecycle.drain(sagaId).entries()).isEmpty();
        }

        @Test
        @DisplayName("complete records the outcome step and adds COMPLETED")
        void complete() {
            String sagaId = started().sagaId();
            lifecycle.drain(sagaId);

            SagaInstance instance = lifecycle.complete(sagaId, ThreeStepSaga.END_STEP);

            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(ThreeStepSaga.END_STEP);
            assertThat(lifecycle.drain(sagaId).entries()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(SagaLifecycleEventKind.COMPLETED);
                assertThat(entry.outcomeStep()).isEqualTo(ThreeStepSaga.END_STEP);
            });
        }

        @Test
        @DisplayName("fail records the failure and adds FAILED")
        void fail() {
            String sagaId = started().sagaId();
            lifecycle.drain(sagaId);
            SagaFailure failure = SagaFailure.of(ThreeStepSaga.RESERVED_STEP, new IllegalStateException("boom"),
                    START);

            SagaInstance instance = lifecycle.fail(sagaId, failure);

            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.failure()).isEqualTo(failure);
            assertThat(lifecycle.drain(sagaId).entries()).singleElement().satisfies(entry -> {
                assertThat(entry.kind()).isEqualTo(SagaLifecycleEventKind.FAILED);
                assertThat(entry.failure()).isEqualTo(failure);
            });
        }

        @Test
        @DisplayName("a terminal working copy rejects every further transition")
        void terminalRejected() {
            String sagaId = started().sagaId();
            lifecycle.complete(sagaId, ThreeStepSaga.END_STEP);
            SagaFailure failure = SagaFailure.of(ThreeStepSaga.END_STEP, new IllegalStateException(), START);

            assertThatThrownBy(() -> lifecycle.recordStepCompleted(sagaId, ThreeStepSaga.END_STEP, List.of()))
                    .isInstanceOf(InvalidSagaTransitionException.class);
            assertThatThrownBy(() -> lifecycle.advance(sagaId)).isInstanceOf(InvalidSagaTransitionException.class);
            assertThatThrownBy(() -> lifecycle.complete(sagaId, ThreeStepSaga.END_STEP))
                    .isInstanceOf(InvalidSagaTransitionException.class);
            assertThatThrownBy(() -> lifecycle.fail(sagaId, failure))
                    .isInstanceOf(InvalidSagaTransitionException.class);
        }

        @Test
        @DisplayName("a sagaId other than the working copy's is not found")
        void otherSagaIdNotFound() {
            started();

            assertThatThrownBy(() -> lifecycle.advance("other")).isInstanceOf(InvalidSagaTransitionException.class);
            assertThatThrownBy(() -> lifecycle.drain("other")).isInstanceOf(InvalidSagaTransitionException.class);
        }

        @Test
        @DisplayName("a lifecycle holding no instance rejects transitions")
        void emptyLifecycleRejects() {
            assertThatThrownBy(() -> lifecycle.advance("saga-1")).isInstanceOf(InvalidSagaTransitionException.class);
        }
    }

    @Nested
    @DisplayName("load and drain")
    class LoadAndDrain {

        @Test
        @DisplayName("drain returns the entries in transition order and clears them")
        void drainInTransitionOrder() {
            String sagaId = started().sagaId();
            lifecycle.recordStepCompleted(sagaId, ThreeStepSaga.START_STEP, List.of(RESERVATION));
            lifecycle.recordStepCompleted(sagaId, ThreeStepSaga.RESERVED_STEP, List.of());
            lifecycle.complete(sagaId, ThreeStepSaga.RESERVED_STEP);

            PendingWrite write = lifecycle.drain(sagaId);

            assertThat(kinds(write)).containsExactly(SagaLifecycleEventKind.CREATED,
                    SagaLifecycleEventKind.STEP_COMPLETED, SagaLifecycleEventKind.STEP_COMPLETED,
                    SagaLifecycleEventKind.COMPLETED);
            assertThat(write.entries()).extracting(SagaLifecycleEntry::stepName)
                    .containsExactly(null, ThreeStepSaga.START_STEP, ThreeStepSaga.RESERVED_STEP, null);
            assertThat(write.entries()).extracting(SagaLifecycleEntry::occurredAt).isSorted();
            assertThat(lifecycle.drain(sagaId).entries()).isEmpty();
            assertThat(lifecycle.drain(sagaId).instance()).isEqualTo(write.instance());
        }

        @Test
        @DisplayName("load seeds the working copy from a locked read")
        void loadSeedsWorkingCopy() {
            SagaInstance locked = stored("saga-9", ThreeStepSaga.class, SagaStatus.RUNNING);
            EngineSagaLifecycle resumed = new EngineSagaLifecycle(threeStep, storage, sources);

            resumed.load(locked);
            resumed.advance("saga-9");

            PendingWrite write = resumed.drain("saga-9");
            assertThat(write.instance().sagaId()).isEqualTo("saga-9");
            assertThat(write.instance().createdAt()).isEqualTo(START);
            assertThat(write.entries()).isEmpty();
        }

        @Test
        @DisplayName("load rejects an instance of another saga type and a second instance")
        void loadRejects() {
            EngineSagaLifecycle resumed = new EngineSagaLifecycle(threeStep, storage, sources);

            assertThatThrownBy(() -> resumed.load(stored("b", CreateBookingSaga.class, SagaStatus.RUNNING)))
                    .isInstanceOf(IllegalArgumentException.class);
            resumed.load(stored("a", ThreeStepSaga.class, SagaStatus.RUNNING));
            assertThatThrownBy(() -> resumed.load(stored("c", ThreeStepSaga.class, SagaStatus.RUNNING)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
