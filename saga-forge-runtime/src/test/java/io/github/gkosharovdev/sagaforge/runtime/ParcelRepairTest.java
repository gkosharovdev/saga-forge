package io.github.gkosharovdev.sagaforge.runtime;

import static io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture.report;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.RefundParcel;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Repairs over {@link ParcelSaga}, whose compensations take a {@link CompensationTrigger} and send
 * outbox commands ({@code specs/015-saga-operations} contracts/operations-api.md RC4 and CP5).
 */
class ParcelRepairTest {

  private static final OperatorRequest BOB = new OperatorRequest("bob", "courier refund settled");

  private final ParcelFixture fixture = new ParcelFixture();

  @Test
  @DisplayName("RC4: a compensation whose outbox command failed runs again and sends it again")
  void resumeAfterFailedCompensationCommand() {
    String sagaId = fixture.packed("p-1");
    fixture.compensating(sagaId, Set.of(ParcelSaga.PACK, ParcelSaga.PAY));
    CommandId refund = CommandId.of(sagaId, ParcelSaga.REFUND, RefundParcel.class.getName(), 1);
    fixture.transport().reportFailure(report(refund, FailureClassification.EXHAUSTED, 10));
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATION_FAILED);
    long refundsBefore = refunds();

    RepairResult result = fixture.engine().operations().resumeCompensation(sagaId, BOB);

    SagaInstance ended = ((RepairResult.Applied) result).instance();
    assertThat(ended.status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(ended.compensationRound()).isEqualTo(1);
    assertThat(ParcelSaga.ran()).containsExactly(ParcelSaga.REFUND, ParcelSaga.CANCEL);
    assertThat(refunds()).isEqualTo(refundsBefore + 1);
  }

  @Test
  @DisplayName(
      "CP5: a compensation taking CompensationTrigger receives OPERATOR_REQUEST and the request")
  void compensationSeesOperatorTrigger() {
    String sagaId = fixture.packed("p-1");

    RepairResult result = fixture.engine().operations().compensate(sagaId, BOB);

    assertThat(((RepairResult.Applied) result).instance().status())
        .isEqualTo(SagaStatus.COMPENSATED);
    assertThat(ParcelSaga.ran())
        .containsExactly(ParcelSaga.UNPACK, ParcelSaga.REFUND, ParcelSaga.CANCEL);
    assertThat(ParcelSaga.triggers())
        .allSatisfy(
            trigger -> {
              assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.OPERATOR_REQUEST);
              assertThat(trigger.operatorRequest()).isEqualTo(BOB);
              assertThat(trigger.stepName()).isEqualTo(ParcelSaga.PACK);
            });
  }

  private long refunds() {
    return fixture.commands().stream()
        .filter(command -> command.command() instanceof RefundParcel)
        .count();
  }
}
