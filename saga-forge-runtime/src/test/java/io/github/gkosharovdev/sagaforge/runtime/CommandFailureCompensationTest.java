package io.github.gkosharovdev.sagaforge.runtime;

import static io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture.CAUSE;
import static io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture.report;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.FailurePhase;
import io.github.gkosharovdev.sagaforge.core.outcome.OutcomeTrigger;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ShipParcel;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport.Ending;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A step's outbox command that failed for good compensates the saga that sent it
 * (specs/012-outbox-command-failure-feedback US1, US2 in the runtime; contracts/runtime-reaction.md
 * §A).
 */
class CommandFailureCompensationTest {

  private static final String SHIP = ShipParcel.class.getName();

  private final ParcelFixture fixture = new ParcelFixture();

  @Test
  @DisplayName(
      "US1 1, 2, 4: a final failure compensates every completed step, the sender first, with no message")
  void finalFailureCompensates() {
    String sagaId = fixture.packed("p-1");
    CommandId sent = fixture.sent(ShipParcel.class);

    fixture.transport().reportFailure(report(sent, FailureClassification.FINAL, 1));

    assertThat(ParcelSaga.ran())
        .containsExactly(ParcelSaga.UNPACK, ParcelSaga.REFUND, ParcelSaga.CANCEL);
    SagaInstance instance = fixture.committed(sagaId);
    assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
    CompensationTrigger trigger = instance.compensationTrigger();
    assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.COMMAND_FAILED);
    assertThat(trigger.stepName()).isEqualTo(ParcelSaga.PACK);
    assertThat(trigger.commandType()).isEqualTo(SHIP);
    assertThat(trigger.failure())
        .isEqualTo(
            new SagaFailure(
                ParcelSaga.PACK, CAUSE, null, trigger.at(), FailureClassification.FINAL, 1));
    assertThat(ParcelSaga.triggers()).containsOnly(trigger);
    assertThat(fixture.outcomes())
        .singleElement()
        .isInstanceOfSatisfying(
            SagaCompensated.class,
            compensated ->
                assertThat(compensated.trigger())
                    .isEqualTo(
                        new OutcomeTrigger(
                            CompensationTriggerKind.COMMAND_FAILED,
                            ParcelSaga.PACK,
                            FailureClassification.FINAL,
                            CAUSE,
                            1,
                            trigger.at(),
                            SHIP)));
    assertThat(fixture.transport().setAsides())
        .singleElement()
        .satisfies(setAside -> assertThat(setAside.ending()).isEqualTo(Ending.COMMITTED));
  }

  @Test
  @DisplayName("US2: an exhausted command records EXHAUSTED and the attempts the transport made")
  void exhaustedFailureCompensates() {
    String sagaId = fixture.packed("p-1");

    fixture
        .transport()
        .reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.EXHAUSTED, 10));

    SagaInstance instance = fixture.committed(sagaId);
    assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(instance.compensationTrigger().kind())
        .isEqualTo(CompensationTriggerKind.COMMAND_FAILED);
    assertThat(instance.compensationTrigger().failure())
        .extracting(SagaFailure::classification, SagaFailure::attempts)
        .containsExactly(FailureClassification.EXHAUSTED, 10);
  }

  @Test
  @DisplayName(
      "US1 3: a failing compensation ends COMPENSATION_FAILED with one SagaFailed in the compensation phase")
  void failingCompensation() {
    String sagaId = fixture.packed("p-1");
    ParcelSaga.failCompensation(ParcelSaga.REFUND);

    fixture
        .transport()
        .reportFailure(report(fixture.sent(ShipParcel.class), FailureClassification.FINAL, 1));

    assertThat(ParcelSaga.ran()).containsExactly(ParcelSaga.UNPACK, ParcelSaga.REFUND);
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATION_FAILED);
    assertThat(fixture.outcomes())
        .singleElement()
        .isInstanceOfSatisfying(
            SagaFailed.class,
            failed -> {
              assertThat(failed.phase()).isEqualTo(FailurePhase.COMPENSATION);
              assertThat(failed.failedStep()).isEqualTo(ParcelSaga.PAY);
              assertThat(failed.compensationTrigger().kind())
                  .isEqualTo(CompensationTriggerKind.COMMAND_FAILED);
              assertThat(failed.compensationTrigger().commandType()).isEqualTo(SHIP);
            });
  }

  @Test
  @DisplayName(
      "R1: a conflicted reaction throws and rolls the set-aside back; the repeated report then compensates")
  void conflictRollsBackTheSetAside() {
    String sagaId = fixture.packed("p-1");
    CommandId sent = fixture.sent(ShipParcel.class);
    fixture.storage().conflictOnNextSave(sagaId);

    assertThatThrownBy(
            () -> fixture.transport().reportFailure(report(sent, FailureClassification.FINAL, 1)))
        .isInstanceOf(SagaConcurrencyConflictException.class);

    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(fixture.transport().setAsides())
        .singleElement()
        .satisfies(setAside -> assertThat(setAside.ending()).isEqualTo(Ending.ROLLED_BACK));
    assertThat(ParcelSaga.ran()).isEmpty();

    fixture.transport().reportFailure(report(sent, FailureClassification.FINAL, 1));

    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(fixture.transport().setAsides())
        .extracting(setAside -> setAside.ending())
        .containsExactly(Ending.ROLLED_BACK, Ending.COMMITTED);
  }
}
