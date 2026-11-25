package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineDurationException;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.DeadlineFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.OrderPlaced;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentDeclined;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.RemindCustomer;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.StockReserved;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 2: several deadlines per saga, durations from the context and from properties,
 * replacement, and a timeout step that re-arms its own deadline (specs/017-saga-deadlines spec.md
 * US2, FR-009, FR-010; tasks.md T029, T030).
 */
class MultipleDeadlinesTest {

  @Test
  @DisplayName(
      "each deadline runs only its own timeout step; the reminder re-arms itself and keeps the saga RUNNING")
  void eachDeadlineItsOwnStep() {
    DeadlineFixture fixture = new DeadlineFixture();
    String sagaId = fixture.placedAndReserved("o-1", "PT1H");

    List<SagaHandlingResult> results = fixture.advance(Duration.ofMinutes(10));

    assertThat(results)
        .extracting(SagaHandlingResult::outcome)
        .containsExactly(HandlingOutcome.WAITING);
    assertThat(fixture.stock().handled()).contains(new RemindCustomer("o-1"));
    SagaInstance instance = fixture.committed(sagaId);
    assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(instance.openDeadlines())
        .containsOnlyKeys(PaymentWindowSaga.WINDOW, PaymentWindowSaga.REMINDER);
    assertThat(instance.openDeadlines().get(PaymentWindowSaga.REMINDER).dueAt())
        .isEqualTo(DeadlineFixture.START.plus(Duration.ofMinutes(20)));
    assertThat(instance.openDeadlines().get(PaymentWindowSaga.REMINDER).startedByStep())
        .isEqualTo(PaymentWindowSaga.REMIND);
    assertThat(PaymentWindowSaga.RECEIVED).isEmpty();

    // The second reminder is due at 20m; re-armed when delivered at 30m, the third is due at 40m.
    fixture.advance(Duration.ofMinutes(20));
    assertThat(fixture.stock().handled().stream().filter(RemindCustomer.class::isInstance))
        .hasSize(2);
    assertThat(fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.REMINDER).dueAt())
        .isEqualTo(DeadlineFixture.START.plus(Duration.ofMinutes(40)));
  }

  @Test
  @DisplayName("a ${key:default} duration resolves through the property resolver")
  void propertyDuration() {
    DeadlineFixture fixture = new DeadlineFixture(Map.of("deadline.reminder", "PT2M"));
    String sagaId = fixture.placedAndReserved("o-1", "PT1H");

    assertThat(fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.REMINDER).dueAt())
        .isEqualTo(DeadlineFixture.START.plus(Duration.ofMinutes(2)));
  }

  @Test
  @DisplayName("cancelling one deadline leaves the other open")
  void cancelOneLeavesOther() {
    DeadlineFixture fixture = new DeadlineFixture();
    String sagaId = fixture.placedAndReserved("o-1", "PT1H");

    fixture.receive(new PaymentDeclined("o-1"));

    assertThat(fixture.committed(sagaId).openDeadlines())
        .containsOnlyKeys(PaymentWindowSaga.REMINDER);
  }

  @Test
  @DisplayName(
      "a re-armed deadline has a new id; the old one, delivered again, is ignored as REPLACED")
  void reArmedDeadlineIgnoresTheOldId() {
    DeadlineFixture fixture = new DeadlineFixture();
    String sagaId = fixture.placedAndReserved("o-1", "PT1H");
    OpenDeadline first = fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.REMINDER);
    DeadlineReached stale =
        (DeadlineReached)
            fixture.outbox().scheduled().stream()
                .filter(message -> message.messageId().equals(first.deadlineId()))
                .findFirst()
                .orElseThrow()
                .payload();

    fixture.advance(Duration.ofMinutes(10));
    assertThat(
            fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.REMINDER).deadlineId())
        .isNotEqualTo(first.deadlineId());

    SagaHandlingResult again = fixture.deliver(stale);
    assertThat(again.outcome()).isEqualTo(HandlingOutcome.IGNORED);
    assertThat(fixture.logger().byEvent("saga.deadline.ignored"))
        .singleElement()
        .satisfies(line -> assertThat(line.keys()).containsEntry("reason", "REPLACED"));
  }

  @Test
  @DisplayName(
      "a durationFrom attribute that holds no ISO-8601 duration fails the step FINAL and compensates")
  void badContextDuration() {
    DeadlineFixture fixture = new DeadlineFixture();
    String sagaId = fixture.receive(new OrderPlaced("o-1", "half an hour")).sagaId();

    fixture.receive(new StockReserved("o-1"));

    SagaInstance instance = fixture.committed(sagaId);
    assertThat(instance.status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(instance.compensationTrigger().failure().causeType())
        .isEqualTo(DeadlineDurationException.class.getName());
    assertThat(instance.compensationTrigger().failure().classification())
        .isEqualTo(FailureClassification.FINAL);
    assertThat(instance.openDeadlines()).isEmpty();
    assertThat(fixture.outbox().scheduled()).isEmpty();
  }
}
