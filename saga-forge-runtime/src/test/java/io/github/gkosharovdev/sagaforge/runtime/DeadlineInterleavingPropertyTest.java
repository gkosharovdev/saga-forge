package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.DeadlineFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentAuthorized;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentDeclined;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox.State;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Properties 41 and 42 under 1,000 randomized orders of the cancelling event, the {@code @EndSaga}
 * event, clock advances and the delivery of a deadline message leased before it could be cancelled
 * (specs/017-saga-deadlines SC-002; tasks.md T033).
 */
class DeadlineInterleavingPropertyTest {

  private static final int RUNS = 1_000;

  private enum Action {
    DECLINE,
    AUTHORIZE,
    ADVANCE,
    DELIVER_LEASED
  }

  @Test
  @DisplayName(
      "the timeout step runs at most once, only while RUNNING with its deadline open; closed deadlines are"
          + " cancelled unless leased")
  void interleavings() {
    for (int seed = 0; seed < RUNS; seed++) {
      run(new Random(seed), seed);
    }
  }

  private static void run(Random random, int seed) {
    DeadlineFixture fixture = new DeadlineFixture();
    String sagaId = fixture.placedAndReserved("o-" + seed, "PT30M");
    String windowId =
        fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.WINDOW).deadlineId();
    boolean leased = random.nextInt(3) == 0;
    if (leased) {
      fixture.outbox().lease(windowId);
    }
    List<Action> actions =
        new ArrayList<>(
            List.of(
                Action.DECLINE,
                Action.AUTHORIZE,
                Action.ADVANCE,
                Action.ADVANCE,
                Action.DELIVER_LEASED));
    Collections.shuffle(actions, random);
    boolean leasedDelivered = false;
    for (Action action : actions) {
      SagaInstance before = fixture.committed(sagaId);
      int receivedBefore = PaymentWindowSaga.RECEIVED.size();
      switch (action) {
        case DECLINE -> fixture.receive(new PaymentDeclined("o-" + seed));
        case AUTHORIZE -> fixture.receive(new PaymentAuthorized("o-" + seed));
        case ADVANCE -> fixture.advance(Duration.ofMinutes(random.nextInt(40)));
        case DELIVER_LEASED -> {
          if (leased && !leasedDelivered) {
            fixture.deliver(windowId);
            leasedDelivered = true;
          }
        }
      }
      if (PaymentWindowSaga.RECEIVED.size() > receivedBefore) {
        OpenDeadline open = before.openDeadlines().get(PaymentWindowSaga.WINDOW);
        assertThat(before.status())
            .as("seed %d: ran while %s", seed, before.status())
            .isEqualTo(SagaStatus.RUNNING);
        assertThat(open).as("seed %d: ran without the deadline open", seed).isNotNull();
        assertThat(PaymentWindowSaga.RECEIVED.get(receivedBefore).deadlineId())
            .isEqualTo(open.deadlineId());
      }
      SagaInstance after = fixture.committed(sagaId);
      if (after.status() != SagaStatus.RUNNING) {
        assertThat(after.openDeadlines()).as("seed %d", seed).isEmpty();
      }
      if (!after.openDeadlines().containsKey(PaymentWindowSaga.WINDOW)) {
        assertThat(fixture.outbox().state(windowId))
            .as("seed %d", seed)
            .isIn(State.CANCELLED, State.DELIVERED, State.LEASED);
      }
    }
    assertThat(PaymentWindowSaga.RECEIVED).as("seed %d", seed).hasSizeLessThanOrEqualTo(1);
  }
}
