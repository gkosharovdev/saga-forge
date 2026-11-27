package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import java.util.ArrayList;
import java.util.List;
import org.jmolecules.architecture.cqrs.CommandHandler;

/** A payment service handling both commands; {@link #failing()} makes every payment throw. */
public final class Payments {

  private final List<String> charged = new ArrayList<>();
  private final List<String> refunds = new ArrayList<>();
  private boolean failing;

  public Payments failing() {
    failing = true;
    return this;
  }

  @CommandHandler
  public void handle(ProcessPayment command) {
    if (failing) {
      throw new IllegalStateException("payment declined");
    }
    String paymentId = "pay-" + command.orderId();
    charged.add(paymentId);
    DomainEvents.record(new PaymentProcessed(command.orderId(), paymentId, command.amount()));
  }

  @CommandHandler
  public void handle(RefundPayment command, CommandId commandId) {
    refunds.add(command.paymentId() + "@" + commandId.value());
  }

  public List<String> charged() {
    return List.copyOf(charged);
  }

  public List<String> refunds() {
    return List.copyOf(refunds);
  }
}
