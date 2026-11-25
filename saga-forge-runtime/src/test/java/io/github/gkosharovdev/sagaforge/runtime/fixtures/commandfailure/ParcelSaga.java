package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Three committed steps, each with a compensation, for {@code
 * specs/012-outbox-command-failure-feedback}: {@link #pack} sends the outbox command {@link
 * ShipParcel}, the compensation {@link #refund} sends {@link RefundParcel}. Compensations journal
 * their name and trigger in {@link #ran()}; {@link #failCompensation} makes one throw.
 */
@Saga
public final class ParcelSaga {

  public static final String BOOK = "book";
  public static final String PAY = "pay";
  public static final String PACK = "pack";
  public static final String CANCEL = "cancelBooking";
  public static final String REFUND = "refund";
  public static final String UNPACK = "unpack";

  private static final List<String> RAN = new CopyOnWriteArrayList<>();
  private static final List<CompensationTrigger> TRIGGERS = new CopyOnWriteArrayList<>();
  private static final Set<String> FAILING = new CopyOnWriteArraySet<>();

  @SagaId private String sagaId;

  private CommandGateway commandGateway;

  /** Clears the journal and the failing compensations. */
  public static void reset() {
    RAN.clear();
    TRIGGERS.clear();
    FAILING.clear();
  }

  /** The compensations that ran, in order. */
  public static List<String> ran() {
    return List.copyOf(RAN);
  }

  /** The triggers the compensations received, in order. */
  public static List<CompensationTrigger> triggers() {
    return List.copyOf(TRIGGERS);
  }

  /** Makes the compensation {@code name} throw after journaling. */
  public static void failCompensation(String name) {
    FAILING.add(name);
  }

  @StartSaga
  @SagaStep(associationProperty = "parcelId")
  public void book(ParcelBooked event) {
    // Nothing to send.
  }

  @SagaStep(associationProperty = "parcelId")
  public void pay(ParcelPaid event) {
    // Nothing to send.
  }

  @SagaStep(associationProperty = "parcelId")
  public void pack(ParcelPacked event) {
    commandGateway.send(new ShipParcel(event.parcelId()));
  }

  @EndSaga
  @SagaStep(associationProperty = "parcelId")
  public void deliver(ParcelDelivered event) {
    // Outcome "deliver".
  }

  @SagaCompensation(forStep = BOOK)
  public void cancelBooking(CompensationTrigger trigger) {
    journal(CANCEL, trigger);
  }

  @SagaCompensation(forStep = PAY)
  public void refund(CompensationTrigger trigger) {
    journal(REFUND, trigger);
    commandGateway.send(new RefundParcel(sagaId));
  }

  @SagaCompensation(forStep = PACK)
  public void unpack(CompensationTrigger trigger) {
    journal(UNPACK, trigger);
  }

  private static void journal(String name, CompensationTrigger trigger) {
    RAN.add(name);
    TRIGGERS.add(trigger);
    if (FAILING.contains(name)) {
      throw new IllegalStateException(name + " failed on purpose");
    }
  }
}
