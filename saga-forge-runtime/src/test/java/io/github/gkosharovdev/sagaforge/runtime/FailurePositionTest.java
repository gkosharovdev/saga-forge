package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FailureFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FailureSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FaultPoint;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FeeCharged;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FeeService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.InjectedFault;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.LabelPrinted;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger.Logged;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider.StoredEntry;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A failure at any position of the unit of work rolls everything back and leaves the saga {@code FAILED}
 * (specs/004-saga-engine tasks.md T057, User Story 2 scenarios 1 to 5, SC-002, FR-005, quickstart.md scenario 5).
 */
class FailurePositionTest {

    private static final String PARCEL = "parcel-1";
    private static final long WEIGHT = 4;

    /**
     * One failure position.
     *
     * @param name          shown in the test name
     * @param arm           arms the failure on a fresh fixture, before the event is received
     * @param failingStep   the step {@code SagaFailure.stepName} must name
     * @param causeType     the exception class {@code SagaFailure.causeType} must name
     * @param feeIdWritten  whether {@code onFeeCharged} wrote its context value before the failure
     */
    record Position(String name, Consumer<FailureFixture> arm, String failingStep, Class<?> causeType,
            boolean feeIdWritten) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Position> positions() {
        Stream<Position> faultPoints = Arrays.stream(FaultPoint.values()).map(point -> new Position(point.name(),
                fixture -> fixture.plan().arm(point), point.failingStep(), InjectedFault.class,
                point.afterFeeIdWritten()));
        Stream<Position> writeAndCommit = Stream.of(
                new Position("INSTANCE_WRITE", fixture -> fixture.plan().duringStep(FailureSaga.END_STEP,
                        () -> fixture.storage().failNextSave(new IllegalStateException("instance write failed"))),
                        FailureSaga.END_STEP, IllegalStateException.class, true),
                new Position("COMMIT", fixture -> fixture.plan().duringStep(FailureSaga.END_STEP,
                        () -> fixture.boundary().failNextCommit(new IllegalStateException("commit failed"))),
                        FailureSaga.END_STEP, IllegalStateException.class, true));
        return Stream.concat(faultPoints, writeAndCommit);
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private static List<SagaLifecycleEventKind> history(FailureFixture fixture, String sagaId) {
        return fixture.storage().history(sagaId).stream().map(StoredEntry::entry).map(SagaLifecycleEntry::kind)
                .toList();
    }

    @Test
    @DisplayName("there are at least 14 failure positions")
    void positionCount() {
        assertThat(positions()).hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    @DisplayName("without a fault the unit of work commits COMPLETED and passes to the outbox LabelPrinted")
    void withoutFault() {
        FailureFixture fixture = new FailureFixture();

        SagaHandlingResult result = onlyResult(fixture.requestParcel(PARCEL, WEIGHT));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(fixture.committed(result.sagaId()).status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(fixture.fees().fees()).containsEntry(FeeService.feeIdFor(PARCEL),
                WEIGHT * FailureFixture.CENTS_PER_KILOGRAM);
        assertThat(fixture.slots().reservations()).hasSize(1);
        assertThat(fixture.labels().printed()).hasSize(1);
        assertThat(fixture.outbox().received()).singleElement()
                .satisfies(batch -> assertThat(batch.events()).singleElement().isInstanceOf(LabelPrinted.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positions")
    @DisplayName("a failure rolls everything back and records FAILED naming the step and the cause")
    void failureAtPosition(Position position) {
        FailureFixture fixture = new FailureFixture();
        position.arm().accept(fixture);

        SagaHandlingResult result = onlyResult(fixture.requestParcel(PARCEL, WEIGHT));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.started()).isTrue();
        assertThat(result.unitOfWorkId()).isNotNull();
        assertThat(result.failure().stepName()).isEqualTo(position.failingStep());
        assertThat(result.failure().causeType()).isEqualTo(position.causeType().getName());
        assertThat(result.failure().failedAt()).isEqualTo(FailureFixture.NOW);

        SagaInstance instance = fixture.committed(result.sagaId());
        assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(instance.failure()).isEqualTo(result.failure());
        assertThat(instance.steps()).containsOnlyKeys(FailureSaga.STEPS_IN_EVENT_ORDER);
        assertThat(instance.steps().values()).containsOnly(StepRecord.notRun());

        assertThat(fixture.noClientChanges()).isTrue();
        assertThat(fixture.outbox().received()).isEmpty();
        assertThat(fixture.dispatcher().dispatched()).isEmpty();

        assertThat(fixture.context(result.sagaId())).containsEntry(FailureSaga.CONTEXT_WEIGHT, WEIGHT);
        if (position.feeIdWritten()) {
            assertThat(fixture.context(result.sagaId()))
                    .containsEntry(FailureSaga.CONTEXT_FEE_ID, FeeService.feeIdFor(PARCEL));
        } else {
            assertThat(fixture.context(result.sagaId())).doesNotContainKey(FailureSaga.CONTEXT_FEE_ID);
        }
        assertThat(history(fixture, result.sagaId()))
                .containsExactly(SagaLifecycleEventKind.CREATED, SagaLifecycleEventKind.FAILED);
        assertThat(fixture.boundary().isActive()).isFalse();
    }

    @Test
    @DisplayName("US2-2: a handler failure the step catches still rolls back and fails the saga")
    void caughtHandlerFailure() {
        FailureFixture fixture = new FailureFixture();
        fixture.plan().arm(FaultPoint.SLOT_HANDLER).catchInFeeStep();

        SagaHandlingResult result = onlyResult(fixture.requestParcel(PARCEL, WEIGHT));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().stepName()).isEqualTo(FailureSaga.FEE_STEP);
        assertThat(result.failure().causeType()).isEqualTo(InjectedFault.class.getName());
        assertThat(result.failure().message()).contains(FaultPoint.SLOT_HANDLER.name());
        assertThat(fixture.committed(result.sagaId()).status()).isEqualTo(SagaStatus.FAILED);
        assertThat(fixture.noClientChanges()).isTrue();
        assertThat(fixture.outbox().received()).isEmpty();
    }

    @Test
    @DisplayName("US2-5: an event for the FAILED instance is UNEXPECTED and has no effect")
    void eventForFailedInstance() {
        FailureFixture fixture = new FailureFixture();
        fixture.plan().arm(FaultPoint.FEE_STEP);
        String sagaId = onlyResult(fixture.requestParcel(PARCEL, WEIGHT)).sagaId();
        SagaInstance failed = fixture.committed(sagaId);

        SagaHandlingResult result = onlyResult(fixture.engine()
                .receive(new FeeCharged(FeeService.feeIdFor(PARCEL), PARCEL)));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
        assertThat(result.sagaId()).isEqualTo(sagaId);
        assertThat(result.unitOfWorkId()).isNull();
        assertThat(fixture.committed(sagaId)).isEqualTo(failed);
        assertThat(fixture.noClientChanges()).isTrue();
        assertThat(history(fixture, sagaId))
                .containsExactly(SagaLifecycleEventKind.CREATED, SagaLifecycleEventKind.FAILED);
    }

    @Nested
    @DisplayName("FR-005: no outcome could be recorded")
    class NotHandled {

        @Test
        @DisplayName("a failing FAILED write raises EventHandlingException; the instance stays as last committed")
        void failedWriteFails() {
            FailureFixture fixture = new FailureFixture();
            IllegalStateException lockFailure = new IllegalStateException("store unavailable");
            fixture.plan().arm(FaultPoint.START_STEP).duringStep(FailureSaga.START_STEP,
                    () -> fixture.storage().failNextFindByIdAndLock(lockFailure));

            EventHandlingException thrown = catchThrowableOfType(EventHandlingException.class,
                    () -> fixture.requestParcel(PARCEL, WEIGHT));

            assertThat(thrown.getCause()).isSameAs(lockFailure);
            assertThat(lockFailure.getSuppressed()).anyMatch(InjectedFault.class::isInstance);
            assertThat(thrown.report().results()).isEmpty();
            assertThat(fixture.storage().committed().all()).singleElement().satisfies(instance -> {
                assertThat(instance.status()).isEqualTo(SagaStatus.CREATED);
                assertThat(history(fixture, instance.sagaId())).containsExactly(SagaLifecycleEventKind.CREATED);
            });
            assertThat(fixture.noClientChanges()).isTrue();
            assertThat(fixture.logger().byEvent("saga.instance.failedNotRecorded")).hasSize(1);
            assertThat(fixture.logger().byEvent("saga.event.notHandled")).singleElement()
                    .extracting(Logged::thrown).isSameAs(thrown);
        }

        @Test
        @DisplayName("a failing creation write raises EventHandlingException and creates nothing")
        void creationWriteFails() {
            FailureFixture fixture = new FailureFixture();
            IllegalStateException saveFailure = new IllegalStateException("creation write failed");
            fixture.storage().failNextSave(saveFailure);

            EventHandlingException thrown = catchThrowableOfType(EventHandlingException.class,
                    () -> fixture.requestParcel(PARCEL, WEIGHT));

            assertThat(thrown.getCause()).isSameAs(saveFailure);
            assertThat(thrown.report().results()).isEmpty();
            assertThat(fixture.storage().committed().all()).isEmpty();
            assertThat(fixture.noClientChanges()).isTrue();
            assertThat(fixture.boundary().isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("diagnostics: the rollback and the FAILED write are logged with the saga id")
    void failureDiagnostics() {
        FailureFixture fixture = new FailureFixture();
        fixture.plan().arm(FaultPoint.SLOT_SAVER);

        String sagaId = onlyResult(fixture.requestParcel(PARCEL, WEIGHT)).sagaId();

        assertThat(fixture.logger().byEvent("saga.uow.rolledBack")).singleElement().satisfies(logged -> {
            assertThat(logged.keys()).containsEntry("sagaId", sagaId)
                    .containsEntry("sagaType", FailureSaga.class.getName())
                    .containsEntry("step", FailureSaga.FEE_STEP);
            assertThat(logged.thrown()).isInstanceOf(InjectedFault.class);
        });
        assertThat(fixture.logger().byEvent("saga.instance.failed")).singleElement()
                .satisfies(logged -> assertThat(logged.keys()).containsEntry("sagaId", sagaId)
                        .containsEntry("step", FailureSaga.FEE_STEP));
    }
}
