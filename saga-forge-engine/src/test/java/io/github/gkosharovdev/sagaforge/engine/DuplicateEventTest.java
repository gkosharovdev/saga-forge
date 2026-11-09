package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency.ChargeSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency.Faults;
import io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency.IdempotencyFixture;
import io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency.OrderPacked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An event for a step that already completed is skipped (specs/005-step-retry User Story 2, FR-006, FR-007,
 * contracts/command-identity.md "Duplicate events", tasks.md T030).
 */
class DuplicateEventTest {

    private static final String ORDER = "order-1";

    private final IdempotencyFixture fixture = new IdempotencyFixture();

    @Test
    @DisplayName("a repeated event for a completed step of a running instance is DUPLICATE and changes nothing")
    void runningInstance() {
        String sagaId = fixture.place(ORDER, 100).sagaId();
        assertThat(fixture.pack(ORDER).outcome()).isEqualTo(HandlingOutcome.WAITING);
        int saves = fixture.storage().saveCalls();
        int shipmentSaves = fixture.shipments().saves().size();

        SagaHandlingResult repeated = fixture.pack(ORDER);

        assertThat(repeated.outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(repeated.sagaId()).isEqualTo(sagaId);
        assertThat(repeated.unitOfWorkId()).isNull();
        assertThat(repeated.started()).isFalse();
        assertThat(fixture.faults().reached(Faults.AFTER_PACKED)).isEqualTo(1);
        assertThat(fixture.shipments().saves()).hasSize(shipmentSaves);
        assertThat(fixture.storage().saveCalls()).isEqualTo(saves);
        assertThat(fixture.logger().byEvent("saga.event.duplicate")).singleElement()
                .satisfies(logged -> assertThat(logged.keys()).containsEntry("step", ChargeSaga.PACKED_STEP));
    }

    @Test
    @DisplayName("an event redelivered after its unit of work rolled back is not a duplicate")
    void afterARollback() {
        String sagaId = fixture.place(ORDER, 100).sagaId();
        fixture.storage().conflictOnNextSave(sagaId);
        assertThat(fixture.pack(ORDER).outcome()).isEqualTo(HandlingOutcome.NOT_HANDLED);
        assertThat(fixture.committed(sagaId).steps().get(ChargeSaga.PACKED_STEP).status())
                .isEqualTo(StepStatus.NOT_RUN);

        assertThat(fixture.pack(ORDER).outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(fixture.faults().reached(Faults.AFTER_PACKED)).isEqualTo(2);
    }

    @Test
    @DisplayName("a repeated event for a completed step of a COMPLETED instance is DUPLICATE, not UNEXPECTED")
    void completedInstance() {
        String sagaId = fixture.place(ORDER, 100).sagaId();
        fixture.pack(ORDER);
        assertThat(fixture.deliver(ORDER).outcome()).isEqualTo(HandlingOutcome.COMPLETED);

        assertThat(fixture.pack(ORDER).outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(fixture.deliver(ORDER).outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("on a FAILED instance, a completed step's event is DUPLICATE and the failed step's is UNEXPECTED")
    void failedInstance() {
        String sagaId = fixture.place(ORDER, 100).sagaId();
        fixture.pack(ORDER);
        fixture.faults().fail(Faults.AFTER_DELIVERED, 1, () -> new IllegalStateException("delivery failed"));
        assertThat(fixture.deliver(ORDER).outcome()).isEqualTo(HandlingOutcome.FAILED);

        assertThat(fixture.pack(ORDER).outcome()).isEqualTo(HandlingOutcome.DUPLICATE);
        assertThat(fixture.deliver(ORDER).outcome()).isEqualTo(HandlingOutcome.UNEXPECTED);
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.FAILED);
    }

    @Test
    @DisplayName("an event recorded twice in one unit of work runs its step once and hands the repeat off")
    void inProcessRepeat() {
        SagaHandlingResult placed = fixture.place(ORDER, ChargeSaga.REPACK_AMOUNT);

        assertThat(placed.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(fixture.faults().reached(Faults.AFTER_PACKED)).isEqualTo(1);
        assertThat(fixture.committed(placed.sagaId()).steps().get(ChargeSaga.PACKED_STEP).status())
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(fixture.handoff().received()).singleElement()
                .satisfies(batch -> assertThat(batch.events()).containsExactly(new OrderPacked(ORDER)));
    }
}
