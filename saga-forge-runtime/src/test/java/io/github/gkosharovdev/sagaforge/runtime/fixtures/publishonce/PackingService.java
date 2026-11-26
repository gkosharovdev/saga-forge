package io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records {@link ShipmentPacked}, which {@link PackingSaga} consumes in process and {@link
 * BillingSaga} starts on; fails once after {@link #failNext()}.
 */
public final class PackingService {

  private final AtomicBoolean failNext = new AtomicBoolean();

  @CommandHandler
  public void handle(PackShipment command) {
    DomainEvents.record(new ShipmentPacked(command.orderId()));
    if (failNext.getAndSet(false)) {
      throw new IllegalStateException("PackingService failure injected");
    }
  }

  /** Makes the next {@code handle} call fail after recording its event. */
  public void failNext() {
    failNext.set(true);
  }
}
