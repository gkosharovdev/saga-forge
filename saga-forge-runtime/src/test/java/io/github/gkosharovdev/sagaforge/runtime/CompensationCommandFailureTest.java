package io.github.gkosharovdev.sagaforge.runtime;

import static io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture.CAUSE;
import static io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture.report;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.FailurePhase;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.RefundParcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ShipParcel;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An outbox command a compensation sent fails for good (specs/012-outbox-command-failure-feedback
 * FR-012a, G-012-01; contracts/runtime-reaction.md §B).
 */
class CompensationCommandFailureTest {

  private final ParcelFixture fixture = new ParcelFixture();

  @Test
  @DisplayName(
      "while compensating: the compensation fails and the compensations not yet run do not run")
  void whileCompensating() {
    String sagaId = fixture.packed("p-1");
    fixture.compensating(sagaId, Set.of(ParcelSaga.PACK, ParcelSaga.PAY));
    CommandId refund = CommandId.of(sagaId, ParcelSaga.REFUND, RefundParcel.class.getName(), 1);

    fixture.transport().reportFailure(report(refund, FailureClassification.EXHAUSTED, 10));

    SagaInstance instance = fixture.committed(sagaId);
    assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATION_FAILED);
    assertThat(instance.steps().get(ParcelSaga.PAY).compensation().status())
        .isEqualTo(CompensationStatus.FAILED);
    assertThat(instance.steps().get(ParcelSaga.PAY).compensation().failure())
        .satisfies(
            failure -> {
              assertThat(failure.causeType()).isEqualTo(CAUSE);
              assertThat(failure.message()).isNull();
              assertThat(failure.classification()).isEqualTo(FailureClassification.EXHAUSTED);
              assertThat(failure.attempts()).isEqualTo(10);
            });
    assertThat(instance.steps().get(ParcelSaga.BOOK).compensation().status())
        .isEqualTo(CompensationStatus.NOT_ATTEMPTED);
    assertThat(ParcelSaga.ran()).isEmpty();
    assertThat(fixture.outcomes())
        .singleElement()
        .isInstanceOfSatisfying(
            SagaFailed.class,
            failed -> {
              assertThat(failed.phase()).isEqualTo(FailurePhase.COMPENSATION);
              assertThat(failed.failedStep()).isEqualTo(ParcelSaga.PAY);
              assertThat(failed.compensationTrigger().kind())
                  .isEqualTo(CompensationTriggerKind.FINAL_FAILURE);
            });
  }

  @Test
  @DisplayName("G-012-01: after the instance ended COMPENSATED, the report is only set aside")
  void afterCompensated() {
    String sagaId = fixture.packed("p-1");
    fixture
        .transport()
        .reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.FINAL, 1));
    SagaInstance compensated = fixture.committed(sagaId);

    fixture
        .transport()
        .reportFailure(report(fixture.sent(RefundParcel.class), FailureClassification.FINAL, 1));

    assertThat(fixture.committed(sagaId)).isEqualTo(compensated);
    assertThat(fixture.logger().byEvent("saga.command.failure.ignored"))
        .singleElement()
        .satisfies(
            logged ->
                assertThat(logged.keys())
                    .containsEntry("reason", "already-compensated")
                    .containsEntry("sender", ParcelSaga.REFUND));
    assertThat(fixture.outcomes()).hasSize(1);
  }
}
