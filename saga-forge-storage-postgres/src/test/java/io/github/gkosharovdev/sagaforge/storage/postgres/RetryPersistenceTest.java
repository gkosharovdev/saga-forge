package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fields {@code specs/005-step-retry} adds, encoded for and decoded from their columns, without a database
 * (contracts/storage-delta.md, tasks.md T062).
 */
class RetryPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00.123456Z");
    private static final AggregateReference ORDER = new AggregateReference("com.example.Order", "order-1");
    private static final CommandId CHARGE = CommandId.of("saga-1", "processPayment", "com.example.Charge", 1);
    private static final CommandId NOTIFY = CommandId.of("saga-1", "processPayment", "com.example.Notify", 1);

    @Test
    @DisplayName("a step's attempts and handled command identities round-trip through the steps JSON")
    void stepsRoundTrip() {
        Map<String, StepRecord> steps = Map.of(
                "processPayment", StepRecord.completed(List.of(ORDER), 3, List.of(CHARGE, NOTIFY)),
                "completeOrder", StepRecord.notRun());

        Map<String, StepRecord> decoded = SagaInstanceRows.decodeSteps(SagaInstanceRows.encodeSteps(steps));

        assertThat(decoded).isEqualTo(steps);
        assertThat(decoded.get("processPayment").handledCommands()).containsExactly(CHARGE, NOTIFY);
    }

    @Test
    @DisplayName("steps JSON written before 005 loads with attempts 1 (0 if not run) and no commands")
    void stepsBefore005() {
        // Since 006 a completed step needs the "sequence" that schema-upgrade-006.sql backfills.
        String json = "{\"processPayment\": {\"status\": \"COMPLETED\", \"touchedAggregates\": "
                + "[{\"aggregateType\": \"com.example.Order\", \"aggregateId\": \"order-1\"}], \"sequence\": 1},"
                + " \"completeOrder\": {\"status\": \"NOT_RUN\", \"touchedAggregates\": []}}";

        Map<String, StepRecord> decoded = SagaInstanceRows.decodeSteps(json);

        assertThat(decoded.get("processPayment")).isEqualTo(new StepRecord(StepStatus.COMPLETED, List.of(ORDER)));
        assertThat(decoded.get("processPayment").attempts()).isEqualTo(1);
        assertThat(decoded.get("completeOrder").attempts()).isZero();
    }

    @Test
    @DisplayName("a stored command identity whose fields are inconsistent fails to load with its path")
    void invalidCommandIdentity() {
        String json = "{\"processPayment\": {\"status\": \"COMPLETED\", \"touchedAggregates\": [], \"attempts\": 1,"
                + " \"handledCommands\": [{\"sagaId\": \"saga-1\", \"stepName\": \"processPayment\","
                + " \"commandType\": \"com.example.Charge\", \"ordinal\": 0, \"value\": \"x\"}]}}";

        assertThatThrownBy(() -> SagaInstanceRows.decodeSteps(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saga_instance.steps.processPayment.handledCommands[0]");
    }

    @Test
    @DisplayName("a pending retry round-trips through pending_retry; none is SQL NULL")
    void pendingRetryRoundTrip() {
        PendingRetry retry = new PendingRetry("processPayment", 3, new SagaFailure("processPayment",
                "com.example.CourierTimeout", "timed out", NOW, FailureClassification.TRANSIENT, 2));
        PendingRetry withoutMessage = new PendingRetry("processPayment", 2, new SagaFailure("processPayment",
                "com.example.CourierTimeout", null, NOW, FailureClassification.TRANSIENT, 1));

        assertThat(SagaInstanceRows.decodePendingRetry(SagaInstanceRows.encodePendingRetry(retry))).isEqualTo(retry);
        assertThat(SagaInstanceRows.decodePendingRetry(SagaInstanceRows.encodePendingRetry(withoutMessage)))
                .isEqualTo(withoutMessage);
        assertThat(SagaInstanceRows.encodePendingRetry(null)).isNull();
        assertThat(SagaInstanceRows.decodePendingRetry(null)).isNull();
    }

    private static SagaInstance running(Map<String, StepRecord> steps) {
        return new SagaInstance("saga-1", "com.example.OrderSaga", Map.of(), Map.of("orderId", "order-1"),
                SagaStatus.RUNNING, NOW, NOW, steps, null, null);
    }

    @Test
    @DisplayName("a completed step's attempts or handled commands must not change")
    void forwardTransition() {
        SagaInstance previous = running(Map.of("processPayment", StepRecord.completed(List.of(), 2, List.of(CHARGE))));

        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(previous,
                running(Map.of("processPayment", StepRecord.completed(List.of(), 3, List.of(CHARGE))))))
                .isInstanceOf(ConcurrentSagaUpdateException.class).hasMessageContaining("attempts");
        assertThatThrownBy(() -> ForwardTransition.requireForwardTransition(previous,
                running(Map.of("processPayment", StepRecord.completed(List.of(), 2, List.of(CHARGE, NOTIFY))))))
                .isInstanceOf(ConcurrentSagaUpdateException.class).hasMessageContaining("handledCommands");
        ForwardTransition.requireForwardTransition(previous, previous);
    }
}
