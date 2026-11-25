package io.github.gkosharovdev.sagaforge.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure.ParcelSaga;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Sender#resolve}: the method named by {@code CommandId.stepName()} (012
 * contracts/runtime-reaction.md).
 */
class SenderTest {

  private final SagaModel saga = SagaModel.of(ParcelSaga.class);

  @Test
  @DisplayName("a step name resolves to the step")
  void step() {
    assertThat(Sender.resolve(saga, ParcelSaga.PACK))
        .isEqualTo(new Sender.StepSender(ParcelSaga.PACK));
  }

  @Test
  @DisplayName("a compensation name resolves to the compensation and the step it undoes")
  void compensation() {
    assertThat(Sender.resolve(saga, ParcelSaga.REFUND))
        .isEqualTo(new Sender.CompensationSender(ParcelSaga.REFUND, ParcelSaga.PAY));
  }

  @Test
  @DisplayName("any other name is unknown")
  void unknown() {
    assertThat(Sender.resolve(saga, "packRenamed")).isEqualTo(new Sender.Unknown("packRenamed"));
  }
}
