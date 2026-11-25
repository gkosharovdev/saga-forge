package io.github.gkosharovdev.sagaforge.runtime;

import static io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture.report;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelDelivered;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ShipParcel;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger.Logged;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport.Ending;
import java.lang.System.Logger.Level;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Reports an instance cannot react to change nothing but set the message aside, with one diagnostic each
 * (specs/012-outbox-command-failure-feedback US3, FR-012, FR-013; contracts/runtime-reaction.md "Diagnostics").
 */
class IgnoredCommandFailureTest {

    private final ParcelFixture fixture = new ParcelFixture();

    private void assertIgnored(String sagaId, String reason, SagaInstance before, int outcomesBefore) {
        if (before != null) {
            assertThat(fixture.committed(sagaId)).isEqualTo(before);
        }
        assertThat(fixture.outcomes()).hasSize(outcomesBefore);
        assertThat(fixture.logger().byEvent("saga.command.failure.ignored")).singleElement()
                .satisfies(logged -> assertIgnoredLine(logged, sagaId, reason));
        assertThat(fixture.transport().setAsides()).last()
                .satisfies(setAside -> assertThat(setAside.ending()).isEqualTo(Ending.COMMITTED));
    }

    private static void assertIgnoredLine(Logged logged, String sagaId, String reason) {
        assertThat(logged.level()).isEqualTo(Level.WARNING);
        assertThat(logged.keys()).containsEntry("sagaId", sagaId).containsEntry("reason", reason)
                .containsEntry("command", ShipParcel.class.getName()).doesNotContainKey("message");
    }

    @Test
    @DisplayName("US3 1: a completed instance stays as it is")
    void completed() {
        String sagaId = fixture.packed("p-1");
        fixture.receive(new ParcelDelivered("p-1"));
        SagaInstance before = fixture.committed(sagaId);
        int outcomes = fixture.outcomes().size();
        int history = fixture.storage().history(sagaId).size();

        fixture.transport().reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.FINAL, 1));

        assertIgnored(sagaId, "terminal:COMPLETED", before, outcomes);
        assertThat(fixture.storage().history(sagaId)).hasSize(history);
        assertThat(ParcelSaga.ran()).isEmpty();
    }

    @Test
    @DisplayName("US3 2: a missing instance is reported and nothing else happens")
    void missing() {
        CommandId unknown = CommandId.of("saga-404", ParcelSaga.PACK, ShipParcel.class.getName(), 1);

        fixture.transport().reportFailure(report(unknown, FailureClassification.FINAL, 1));

        assertIgnored("saga-404", "missing", null, 0);
    }

    @Test
    @DisplayName("US3 3, FR-013: a repeated report after the compensation changes nothing")
    void repeatedReport() {
        String sagaId = fixture.packed("p-1");
        CommandId sent = fixture.sent(ShipParcel.class);
        fixture.transport().reportFailure(report(sent, FailureClassification.FINAL, 1));
        SagaInstance compensated = fixture.committed(sagaId);

        fixture.transport().reportFailure(report(sent, FailureClassification.FINAL, 1));

        assertIgnored(sagaId, "terminal:COMPENSATED", compensated, 1);
        assertThat(ParcelSaga.ran()).hasSize(3);
    }

    @Test
    @DisplayName("US3 4, FR-012: an instance already compensating is not compensated again")
    void alreadyCompensating() {
        String sagaId = fixture.packed("p-1");
        fixture.compensating(sagaId, Set.of());
        SagaInstance before = fixture.committed(sagaId);

        fixture.transport().reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.FINAL, 1));

        assertIgnored(sagaId, "compensating", before, 0);
        assertThat(ParcelSaga.ran()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = FailureClassification.class, names = {"FINAL", "EXHAUSTED"})
    @DisplayName("a sender the saga no longer declares is reported as unknown")
    void unknownSender(FailureClassification classification) {
        String sagaId = fixture.packed("p-1");
        SagaInstance before = fixture.committed(sagaId);
        CommandId renamed = CommandId.of(sagaId, "packRenamed", ShipParcel.class.getName(), 1);

        fixture.transport().reportFailure(report(renamed, classification, 1));

        assertIgnored(sagaId, "unknown-sender", before, 0);
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
    }
}
