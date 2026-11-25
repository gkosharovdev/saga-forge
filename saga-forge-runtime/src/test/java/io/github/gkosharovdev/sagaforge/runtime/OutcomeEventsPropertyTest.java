package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.outcome.FailurePhase;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompleted;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CourierBusy;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CourierUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.DeliveryFixture;
import java.util.List;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 38 (specs/011-saga-outcome-events): over random step outcomes, failures and compensation
 * failures of the delivery saga, an instance that reaches a terminal status has handed exactly one
 * outcome event of the matching kind to the outbox, with identity {@code sagaId:status}, and a
 * non-terminal instance none.
 */
class OutcomeEventsPropertyTest {

  private static final String ORDER = "o-1";

  /** How the start step's charge fails, if it does. */
  enum Fault {
    NONE,
    FINAL,
    TRANSIENT,
    UNCLASSIFIED;

    RuntimeException exception() {
      return switch (this) {
        case NONE -> null;
        case FINAL -> new CourierUnavailable();
        case TRANSIENT -> new CourierBusy();
        case UNCLASSIFIED -> new IllegalStateException("unmarked");
      };
    }
  }

  /** The event that may end the saga after the payment. */
  enum Ending {
    NONE,
    ACCEPT,
    REJECT,
    STOCK,
    COURIER
  }

  record Scenario(
      Fault chargeFault, boolean charged, Ending ending, Fault courierFault, boolean refundFails) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    return Combinators.combine(
            Arbitraries.of(Fault.class),
            Arbitraries.of(true, false),
            Arbitraries.of(Ending.class),
            Arbitraries.of(Fault.class),
            Arbitraries.of(true, false))
        .as(Scenario::new);
  }

  @Property(tries = 200)
  void everyTerminalInstanceIsAnnouncedOnce(@ForAll("scenarios") Scenario scenario) {
    DeliveryFixture fixture = new DeliveryFixture();
    RuntimeException chargeFailure = scenario.chargeFault().exception();
    if (chargeFailure != null) {
      fixture.payments().failChargesWith(chargeFailure);
    }
    String sagaId = fixture.place(ORDER, 100).sagaId();
    if (scenario.charged()) {
      quietly(() -> fixture.charged(ORDER, "pay-1"));
    }
    if (scenario.refundFails()) {
      fixture.payments().failRefundsWith(new IllegalStateException("refund refused"));
    }
    RuntimeException courierFailure = scenario.courierFault().exception();
    if (courierFailure != null) {
      fixture.couriers().failWith(courierFailure);
    }
    switch (scenario.ending()) {
      case NONE -> {}
      case ACCEPT -> quietly(() -> fixture.accept(ORDER));
      case REJECT -> quietly(() -> fixture.reject(ORDER, "address unknown"));
      case STOCK -> quietly(() -> fixture.stockUnavailable(ORDER));
      case COURIER -> quietly(() -> fixture.requestCourier(ORDER));
    }

    SagaInstance instance = fixture.committed(sagaId);
    List<SagaOutcome> outcomes = fixture.outbox().outcomes();
    switch (instance.status()) {
      case COMPLETED -> assertThat(outcomes).singleElement().isInstanceOf(SagaCompleted.class);
      case COMPENSATED -> assertThat(outcomes).singleElement().isInstanceOf(SagaCompensated.class);
      case FAILED ->
          assertThat(outcomes)
              .singleElement()
              .isInstanceOfSatisfying(
                  SagaFailed.class,
                  failed -> assertThat(failed.phase()).isEqualTo(FailurePhase.STEP));
      case COMPENSATION_FAILED ->
          assertThat(outcomes)
              .singleElement()
              .isInstanceOfSatisfying(
                  SagaFailed.class,
                  failed -> assertThat(failed.phase()).isEqualTo(FailurePhase.COMPENSATION));
      default -> assertThat(outcomes).isEmpty();
    }
    outcomes.forEach(
        outcome -> assertThat(outcome.eventId()).isEqualTo(sagaId + ":" + instance.status()));
    assertThat(
            instance.status() == SagaStatus.CREATED
                || instance.status() == SagaStatus.RUNNING
                || instance.status() == SagaStatus.COMPENSATING)
        .isEqualTo(outcomes.isEmpty());
  }

  /**
   * Runs a delivery whose report may hold no result (an event no instance reacts to); its outcome
   * is checked after.
   */
  private static void quietly(Supplier<?> delivery) {
    try {
      delivery.get();
    } catch (IllegalStateException noSingleResult) {
      // DeliveryFixture expects exactly one result; an unmatched event yields none, which is fine
      // here.
    }
  }
}
