package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CancelPickup;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CollectParcel;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CourierRefused;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CourierSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CourierService;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.DeliveryBooked;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.PickupScheduled;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.ReleaseSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A failing outbox command in a fixture test ({@code specs/012-outbox-command-failure-feedback}
 * contracts/fixture.md, tasks.md T036): the saga that sent it reacts in the same {@code when} step.
 */
class OutboxCommandFailureTest {

  private static SagaFixture.Builder courier() {
    return SagaFixture.of(CourierSaga.class).register(CourierService.class);
  }

  private static Then scheduled(SagaFixture fixture) {
    return fixture.given(new DeliveryBooked("d-1")).when(new PickupScheduled("d-1"));
  }

  @Test
  @DisplayName(
      "a FinalFailure: one attempt, every committed step compensated, COMPENSATED with COMMAND_FAILED")
  void finalFailureCompensates() {
    SagaFixture fixture =
        courier()
            .failCommand(CollectParcel.class, CourierRefused::new)
            .outboxCommandAttempts(3)
            .build();

    Then then =
        scheduled(fixture)
            .expectStatus(SagaStatus.COMPENSATED)
            .expectCompensatedAfter(Courier.SCHEDULED)
            .expectCompensations("cancelPickup", "releaseSlot")
            .expectCommands(new CancelPickup("d-1"), new ReleaseSlot("d-1"))
            .expectFailure(CourierRefused.class, FailureClassification.FINAL)
            .expectOutcomeEvent(
                SagaCompensated.class,
                compensated ->
                    compensated.trigger().kind() == CompensationTriggerKind.COMMAND_FAILED
                        && compensated.trigger().commandType().equals(CollectParcel.class.getName())
                        && compensated.trigger().stepName().equals(Courier.SCHEDULED));

    assertThat(then.result().outboxFailures()).singleElement().isInstanceOf(CourierRefused.class);
    assertThat(then.result().allAttempts())
        .filteredOn(c -> c.command() instanceof CollectParcel)
        .hasSize(1);
    assertThat(then.result().instances())
        .singleElement()
        .satisfies(
            instance ->
                assertThat(instance.compensationTrigger().orElseThrow().failure())
                    .satisfies(
                        failure -> {
                          assertThat(failure.attempts()).isEqualTo(1);
                          assertThat(failure.message()).isNull();
                        }));
  }

  @Test
  @DisplayName(
      "two failures, then a success within outboxCommandAttempts(3): three attempts, no compensation")
  void recoversWithinAttempts() {
    SagaFixture fixture =
        courier()
            .failCommand(CollectParcel.class, () -> new IllegalStateException("courier busy"), 2)
            .outboxCommandAttempts(3)
            .build();

    Then then =
        scheduled(fixture)
            .expectStatus(SagaStatus.RUNNING)
            .expectCompensations()
            .expectNoOutcomeEvent();

    assertThat(then.result().outboxFailures()).hasSize(2);
    assertThat(then.result().allAttempts())
        .filteredOn(c -> c.command() instanceof CollectParcel)
        .hasSize(3);
  }

  @Test
  @DisplayName("an unmarked failure with the default of one attempt is reported EXHAUSTED at once")
  void unmarkedExhaustedByDefault() {
    SagaFixture fixture =
        courier()
            .failCommand(CollectParcel.class, () -> new IllegalStateException("courier busy"))
            .build();

    scheduled(fixture)
        .expectStatus(SagaStatus.COMPENSATED)
        .expectFailure(IllegalStateException.class, FailureClassification.EXHAUSTED)
        .expectOutcomeEvent(
            SagaCompensated.class,
            compensated ->
                compensated.trigger().classification() == FailureClassification.EXHAUSTED
                    && compensated.trigger().attempts() == 1);
  }

  @Test
  @DisplayName("outboxCommandAttempts below 1 is rejected")
  void attemptsMustBePositive() {
    assertThatThrownBy(() -> courier().outboxCommandAttempts(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 1");
  }
}
