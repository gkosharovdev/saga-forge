package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.DeadlineFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentAuthorized;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentDeclined;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.ReleaseStock;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.RemindCustomer;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.ReserveStock;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox.State;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 1: a deadline opens when its step commits, fires once after it is due and compensates
 * (specs/017-saga-deadlines spec.md US1, FR-011 to FR-013; tasks.md T025).
 */
class DeadlineExpiryTest {

  private final DeadlineFixture fixture = new DeadlineFixture();

  @Test
  @DisplayName(
      "the step that starts a deadline schedules it, due at clock + duration, and records it open")
  void startingStepOpensTheDeadline() {
    String sagaId = fixture.placedAndReserved("o-1", "PT30M");

    SagaInstance instance = fixture.committed(sagaId);
    OpenDeadline window = instance.openDeadlines().get(PaymentWindowSaga.WINDOW);
    assertThat(window.dueAt()).isEqualTo(DeadlineFixture.START.plus(Duration.ofMinutes(30)));
    assertThat(window.startedByStep()).isEqualTo(PaymentWindowSaga.RESERVED);
    assertThat(window.deadlineId()).startsWith(sagaId + ":deadline:paymentWindow:uow-");
    ScheduledMessage message =
        fixture.outbox().scheduled().stream()
            .filter(scheduled -> scheduled.messageId().equals(window.deadlineId()))
            .findFirst()
            .orElseThrow();
    assertThat(message.notBefore()).isEqualTo(window.dueAt());
    assertThat(((DeadlineReached) message.payload()).sagaType())
        .isEqualTo(PaymentWindowSaga.class.getName());
  }

  @Test
  @DisplayName("before it is due nothing runs; after it the timeout step runs once and compensates")
  void timesOutAndCompensates() {
    String sagaId = fixture.placedAndReserved("o-1", "PT30M");
    fixture.advance(Duration.ofMinutes(9));
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(PaymentWindowSaga.RECEIVED).isEmpty();

    List<SagaHandlingResult> results = fixture.advance(Duration.ofMinutes(21));

    assertThat(PaymentWindowSaga.RECEIVED)
        .singleElement()
        .satisfies(
            reached -> assertThat(reached.deadlineName()).isEqualTo(PaymentWindowSaga.WINDOW));
    assertThat(results)
        .extracting(SagaHandlingResult::outcome)
        .contains(HandlingOutcome.COMPENSATED);
    SagaInstance instance = fixture.committed(sagaId);
    assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(instance.openDeadlines()).isEmpty();
    CompensationTrigger trigger = instance.compensationTrigger();
    assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.DEADLINE_EXPIRED);
    assertThat(trigger.deadlineName()).isEqualTo(PaymentWindowSaga.WINDOW);
    assertThat(trigger.stepName()).isEqualTo(PaymentWindowSaga.EXPIRED);
    assertThat(trigger.eventType()).isEqualTo(DeadlineReached.class.getName());
    assertThat(fixture.stock().handled())
        .containsExactly(
            new ReserveStock("o-1"), new RemindCustomer("o-1"), new ReleaseStock("o-1"));
  }

  @Test
  @DisplayName("the outcome event carries the deadline trigger")
  void outcomeCarriesDeadline() {
    String sagaId = fixture.placedAndReserved("o-1", "PT30M");

    fixture.advance(Duration.ofMinutes(30));

    SagaCompensated outcome =
        fixture.outbox().committed().stream()
            .flatMap(batch -> batch.events().stream())
            .filter(SagaCompensated.class::isInstance)
            .map(SagaCompensated.class::cast)
            .filter(compensated -> compensated.sagaId().equals(sagaId))
            .findFirst()
            .orElseThrow();
    assertThat(outcome.trigger().deadlineName()).isEqualTo(PaymentWindowSaga.WINDOW);
    assertThat(outcome.trigger().kind()).isEqualTo(CompensationTriggerKind.DEADLINE_EXPIRED);
  }

  @Test
  @DisplayName("leaving RUNNING cancels every open deadline in the same transaction")
  void endCancelsAll() {
    String sagaId = fixture.placedAndReserved("o-1", "PT30M");
    List<String> open =
        fixture.committed(sagaId).openDeadlines().values().stream()
            .map(OpenDeadline::deadlineId)
            .toList();

    SagaHandlingResult result = fixture.receive(new PaymentAuthorized("o-1"));

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
    assertThat(fixture.committed(sagaId).openDeadlines()).isEmpty();
    assertThat(open)
        .hasSize(2)
        .allSatisfy(id -> assertThat(fixture.outbox().state(id)).isEqualTo(State.CANCELLED));
    assertThat(fixture.advance(Duration.ofHours(2))).isEmpty();
  }

  @Test
  @DisplayName(
      "a cancelledBy step before the due time cancels that deadline only; the saga keeps waiting")
  void cancelledByStep() {
    String sagaId = fixture.placedAndReserved("o-1", "PT30M");
    String window =
        fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.WINDOW).deadlineId();

    fixture.receive(new PaymentDeclined("o-1"));

    SagaInstance instance = fixture.committed(sagaId);
    assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(instance.openDeadlines()).containsOnlyKeys(PaymentWindowSaga.REMINDER);
    assertThat(fixture.outbox().state(window)).isEqualTo(State.CANCELLED);
    fixture.advance(Duration.ofMinutes(31));
    assertThat(PaymentWindowSaga.RECEIVED).isEmpty();
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
  }

  @Test
  @DisplayName("open deadlines survive a restart and fire once after it")
  void survivesRestart() {
    String sagaId = fixture.placedAndReserved("o-1", "PT30M");

    fixture.restart();
    fixture.advance(Duration.ofHours(1));

    assertThat(PaymentWindowSaga.RECEIVED).hasSize(1);
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
  }
}
