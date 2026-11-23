package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.FailurePhase;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CourierBusy;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CourierUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliveryFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliverySaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindSaga;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Outcome events (specs/011-saga-outcome-events US1, US2; quickstart Q1 to Q7 and Q16): every terminal transition hands
 * exactly one {@link SagaOutcome} to the outbox, last in the batch of the write that made the instance terminal.
 */
class OutcomeEventsTest {

    private static final String ORDER = "o-1";

    private static <T extends SagaOutcome> T onlyOutcome(List<SagaOutcome> outcomes, Class<T> type) {
        assertThat(outcomes).hasSize(1).first().isInstanceOf(type);
        return type.cast(outcomes.get(0));
    }

    /** The one batch holding an outcome event, which it carries last. */
    private static OutgoingMessages outcomeBatch(List<OutgoingMessages> batches) {
        List<OutgoingMessages> withOutcome = batches.stream()
                .filter(batch -> batch.events().stream().anyMatch(SagaOutcome.class::isInstance)).toList();
        assertThat(withOutcome).hasSize(1);
        OutgoingMessages batch = withOutcome.get(0);
        assertThat(batch.events().get(batch.events().size() - 1)).isInstanceOf(SagaOutcome.class);
        assertThat(batch.recordedEvents().get(batch.recordedEvents().size() - 1))
                .isSameAs(batch.events().get(batch.events().size() - 1));
        return batch;
    }

    @Nested
    @DisplayName("US1: a FAILED write announces the failure")
    class Failure {

        @Test
        @DisplayName("Q1: a FinalFailure in the start step yields one SagaFailed(STEP, FINAL) in its own batch")
        void finalFailureInStartStep() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.payments().failChargesWith(new CourierUnavailable());

            SagaHandlingResult result = fixture.place(ORDER, 100);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
            SagaFailed failed = onlyOutcome(fixture.outbox().outcomes(), SagaFailed.class);
            assertThat(failed.eventId()).isEqualTo(result.sagaId() + ":FAILED");
            assertThat(failed.sagaType()).isEqualTo(DeliverySaga.class.getName());
            assertThat(failed.sagaId()).isEqualTo(result.sagaId());
            assertThat(failed.associations()).isEqualTo(Map.of("orderId", ORDER));
            assertThat(failed.occurredAt()).isEqualTo(DeliveryFixture.NOW);
            assertThat(failed.phase()).isEqualTo(FailurePhase.STEP);
            assertThat(failed.failedStep()).isEqualTo(DeliverySaga.PLACED);
            assertThat(failed.classification()).isEqualTo(FailureClassification.FINAL);
            assertThat(failed.causeType()).isEqualTo(CourierUnavailable.class.getName());
            assertThat(failed.attempts()).isEqualTo(1);
            assertThat(failed.compensationTrigger()).isNull();

            // The start step rolled back: the FAILED write's batch is the only one, holding only the outcome event.
            assertThat(fixture.outbox().committed()).singleElement().satisfies(batch -> {
                assertThat(batch.events()).containsExactly(failed);
                assertThat(batch.commands()).isEmpty();
                assertThat(batch.unitOfWorkId()).isEqualTo(result.unitOfWorkId());
                assertThat(batch.sagaId()).isEqualTo(result.sagaId());
                // The failure message stays in storage; the event carries none (data-model.md).
                assertThat(batch.toString()).doesNotContain(new CourierUnavailable().getMessage());
            });
        }

        @Test
        @DisplayName("Q2: retries exhausted before any commit yield SagaFailed(STEP, EXHAUSTED)")
        void exhaustedBeforeAnyCommit() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.payments().failChargesWith(new CourierBusy());

            SagaHandlingResult result = fixture.place(ORDER, 100);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
            SagaFailed failed = onlyOutcome(fixture.outbox().outcomes(), SagaFailed.class);
            assertThat(failed.classification()).isEqualTo(FailureClassification.EXHAUSTED);
            assertThat(failed.attempts()).isEqualTo(result.failure().attempts());
            assertThat(failed.causeType()).isEqualTo(CourierBusy.class.getName());
        }

        @Test
        @DisplayName("an unclassified failure after an earlier commit yields SagaFailed(STEP, UNCLASSIFIED) only")
        void unclassifiedAfterCommit() {
            DeliveryFixture fixture = new DeliveryFixture();
            String sagaId = fixture.placedAndCharged(ORDER);
            fixture.couriers().failWith(new IllegalStateException("bug in the courier adapter"));

            SagaHandlingResult result = fixture.requestCourier(ORDER);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
            SagaFailed failed = onlyOutcome(fixture.outbox().outcomes(), SagaFailed.class);
            assertThat(failed.sagaId()).isEqualTo(sagaId);
            assertThat(failed.failedStep()).isEqualTo(DeliverySaga.COURIER);
            assertThat(failed.classification()).isEqualTo(FailureClassification.UNCLASSIFIED);
            assertThat(outcomeBatch(fixture.outbox().committed()).events()).containsExactly(failed);
        }

        @Test
        @DisplayName("Q3: a FAILED write that finds the instance terminal records nothing and yields no event")
        void terminalAtTheFailedWrite() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.withStorage(TerminalAfterFirstLock::new);
            fixture.payments().failChargesWith(new CourierUnavailable());

            SagaHandlingResult result = fixture.place(ORDER, 100);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
            assertThat(fixture.logger().byEvent("saga.instance.failedNotRecorded")).hasSize(1);
            assertThat(fixture.outbox().outcomes()).isEmpty();
        }

        @Test
        @DisplayName("an AFTER_COMMIT outbox failing on the outcome batch leaves FAILED and reports it (O9)")
        void afterCommitOutboxFailure() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.payments().failChargesWith(new CourierUnavailable());
            IllegalStateException brokerDown = new IllegalStateException("broker unavailable");
            fixture.outbox().failNext(brokerDown);

            SagaHandlingResult result = fixture.place(ORDER, 100);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
            assertThat(result.outboxFailures()).containsExactly(brokerDown);
            assertThat(fixture.committed(result.sagaId()).status()).isEqualTo(SagaStatus.FAILED);
            assertThat(fixture.outbox().received()).singleElement()
                    .satisfies(batch -> assertThat(batch.events()).singleElement().isInstanceOf(SagaFailed.class));
        }
    }

    @Nested
    @DisplayName("US2: every way a saga ends is announced")
    class Endings {

        @Test
        @DisplayName("Q4: completion yields SagaCompleted, last in the completing unit of work's batch")
        void completion() {
            DeliveryFixture fixture = new DeliveryFixture();
            String sagaId = fixture.placedAndCharged(ORDER);

            SagaHandlingResult result = fixture.accept(ORDER);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
            SagaCompleted completed = onlyOutcome(fixture.outbox().outcomes(), SagaCompleted.class);
            assertThat(completed.eventId()).isEqualTo(sagaId + ":COMPLETED");
            assertThat(completed.outcomeStep()).isEqualTo(DeliverySaga.ACCEPTED);
            assertThat(outcomeBatch(fixture.outbox().committed()).unitOfWorkId()).isEqualTo(result.unitOfWorkId());
        }

        @Test
        @DisplayName("Q5: a @StartCompensation step after a commit yields SagaCompensated with its trigger")
        void businessFailureCompensates() {
            DeliveryFixture fixture = new DeliveryFixture();
            String sagaId = fixture.placedAndCharged(ORDER);

            SagaHandlingResult result = fixture.reject(ORDER, "address unknown");

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
            SagaCompensated compensated = onlyOutcome(fixture.outbox().outcomes(), SagaCompensated.class);
            assertThat(compensated.eventId()).isEqualTo(sagaId + ":COMPENSATED");
            assertThat(compensated.trigger().kind()).isEqualTo(CompensationTriggerKind.FAILURE_OUTCOME);
            assertThat(compensated.trigger().stepName()).isEqualTo(DeliverySaga.REJECTED);
            outcomeBatch(fixture.outbox().committed());
        }

        @Test
        @DisplayName("Q5: a @StartCompensation step with nothing to compensate yields SagaCompensated at once")
        void businessFailureWithNothingToCompensate() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.place(ORDER, 100);

            SagaHandlingResult result = fixture.stockUnavailable(ORDER);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
            assertThat(onlyOutcome(fixture.outbox().outcomes(), SagaCompensated.class).trigger().stepName())
                    .isEqualTo(DeliverySaga.STOCK);
        }

        @Test
        @DisplayName("a final failure after a commit compensates and yields SagaCompensated(FINAL_FAILURE)")
        void finalFailureCompensates() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.placedAndCharged(ORDER);
            fixture.couriers().failWith(new CourierUnavailable());

            SagaHandlingResult result = fixture.requestCourier(ORDER);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
            SagaCompensated compensated = onlyOutcome(fixture.outbox().outcomes(), SagaCompensated.class);
            assertThat(compensated.trigger().kind()).isEqualTo(CompensationTriggerKind.FINAL_FAILURE);
            assertThat(compensated.trigger().stepName()).isEqualTo(DeliverySaga.COURIER);
            assertThat(compensated.trigger().classification()).isEqualTo(FailureClassification.FINAL);
            assertThat(compensated.trigger().causeType()).isEqualTo(CourierUnavailable.class.getName());
        }

        @Test
        @DisplayName("a final failure with nothing to compensate ends COMPENSATED in the start write, announced once")
        void finalFailureWithNothingToCompensate() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.place(ORDER, 100);
            fixture.couriers().failWith(new CourierUnavailable());

            SagaHandlingResult result = fixture.requestCourier(ORDER);

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
            assertThat(onlyOutcome(fixture.outbox().outcomes(), SagaCompensated.class).trigger().kind())
                    .isEqualTo(CompensationTriggerKind.FINAL_FAILURE);
        }

        @Test
        @DisplayName("Q6: a failed compensation yields SagaFailed(COMPENSATION) with the trigger")
        void failedCompensation() {
            DeliveryFixture fixture = new DeliveryFixture();
            String sagaId = fixture.placedAndCharged(ORDER);
            fixture.payments().failRefundsWith(new IllegalStateException("refund refused"));

            SagaHandlingResult result = fixture.reject(ORDER, "address unknown");

            assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATION_FAILED);
            SagaFailed failed = onlyOutcome(fixture.outbox().outcomes(), SagaFailed.class);
            assertThat(failed.eventId()).isEqualTo(sagaId + ":COMPENSATION_FAILED");
            assertThat(failed.phase()).isEqualTo(FailurePhase.COMPENSATION);
            assertThat(failed.failedStep()).isEqualTo(DeliverySaga.CHARGED);
            assertThat(failed.causeType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(failed.compensationTrigger().kind()).isEqualTo(CompensationTriggerKind.FAILURE_OUTCOME);
            assertThat(failed.compensationTrigger().stepName()).isEqualTo(DeliverySaga.REJECTED);
            assertThat(outcomeBatch(fixture.outbox().committed()).events()).containsExactly(failed);
        }

        @Test
        @DisplayName("a compensation resumed after a crash yields one SagaCompensated")
        void resumedCompensation() {
            UnwindFixture fixture = UnwindFixture.withEveryCompensation();
            fixture.start(ORDER);
            fixture.receive(new BDone(ORDER));
            fixture.receive(new CDone(ORDER));
            CompensationFaults faults = fixture.withFaults();
            faults.crashOnLock(3);
            assertThat(catchThrowable(() -> fixture.receive(new Aborted(ORDER)))).isNotNull();
            assertThat(fixture.outbox().outcomes()).isEmpty();

            SagaHandlingResult resumed = fixture.restart().receive(new Aborted(ORDER));

            assertThat(resumed.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
            assertThat(onlyOutcome(fixture.outbox().outcomes(), SagaCompensated.class).trigger().stepName())
                    .isEqualTo(UnwindSaga.ABORT);
        }

        @Test
        @DisplayName("Q7: a further event after the end yields no second outcome event")
        void noSecondEvent() {
            DeliveryFixture fixture = new DeliveryFixture();
            fixture.placedAndCharged(ORDER);
            fixture.accept(ORDER);

            SagaHandlingResult duplicate = fixture.accept(ORDER);
            SagaHandlingResult late = fixture.reject(ORDER, "too late");

            assertThat(duplicate.outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
            assertThat(late.outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
            assertThat(fixture.outbox().outcomes()).hasSize(1);
        }

        @Test
        @DisplayName("Q16: with outcomeEvents(false) no batch carries an outcome event")
        void switchedOff() {
            DeliveryFixture fixture = new DeliveryFixture().withoutOutcomeEvents();
            fixture.placedAndCharged(ORDER);
            fixture.accept(ORDER);
            fixture.payments().failChargesWith(new CourierUnavailable());
            fixture.place("o-2", 100);

            // This saga records no domain events, so with the outcome events off no unit of work has a batch at all,
            // as before 011.
            assertThat(fixture.outbox().received()).isEmpty();
        }
    }

    /**
     * From its second {@code findByIdAndLock} on, reports the instance {@code FAILED} already, as if another process
     * had recorded it first (G-ENG-10).
     */
    private static final class TerminalAfterFirstLock implements StorageProvider {

        private final StorageProvider delegate;
        private final AtomicInteger locks = new AtomicInteger();

        TerminalAfterFirstLock(StorageProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<SagaInstance> findByIdAndLock(String sagaId) {
            Optional<SagaInstance> locked = delegate.findByIdAndLock(sagaId);
            if (locks.incrementAndGet() == 1) {
                return locked;
            }
            return locked.map(instance -> new SagaInstance(instance.sagaId(), instance.sagaType(), instance.state(),
                    instance.associations(), SagaStatus.FAILED, instance.createdAt(), instance.lastModifiedAt(),
                    instance.steps(), null, SagaFailure.of(DeliverySaga.PLACED,
                            new IllegalStateException("recorded elsewhere"), DeliveryFixture.NOW)));
        }

        @Override
        public void save(SagaInstance instance, List<SagaLifecycleEntry> newEntries) {
            delegate.save(instance, newEntries);
        }

        @Override
        public Optional<SagaInstance> findById(String sagaId) {
            return delegate.findById(sagaId);
        }

        @Override
        public List<SagaInstance> findByAssociation(String associationProperty, String associationValue) {
            return delegate.findByAssociation(associationProperty, associationValue);
        }

        @Override
        public void delete(String sagaId) {
            delegate.delete(sagaId);
        }
    }
}
