package io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The commands of {@link ShipmentSaga} and their handler. */
public final class ShipmentCommands {

  /** An outbox command: can be scheduled. */
  @Command(delivery = CommandDelivery.OUTBOX)
  public record Ship(String shipmentId) {}

  /** Handled in the unit of work: cannot be scheduled. */
  @Command
  public record Note(String shipmentId) {}

  private final List<Object> handled = new CopyOnWriteArrayList<>();

  @CommandHandler
  public void ship(Ship command) {
    handled.add(command);
  }

  @CommandHandler
  public void note(Note command) {
    handled.add(command);
  }

  public List<Object> handled() {
    return List.copyOf(handled);
  }
}
