package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jmolecules.architecture.cqrs.CommandHandler;

/**
 * Charges and refunds orders, announcing each charge through {@code events}. A charge of a
 * non-positive amount is declined. Refunds are keyed, so one key refunds once.
 */
public final class Payments {

  private final Consumer<Object> events;
  private final Map<String, BigDecimal> charges = new ConcurrentHashMap<>();
  private final Map<String, BigDecimal> refunds = new ConcurrentHashMap<>();

  /**
   * @param events where a processed payment is announced; the application publishes it
   */
  public Payments(Consumer<Object> events) {
    this.events = Objects.requireNonNull(events, "events");
  }

  @CommandHandler
  public void handle(ProcessPayment command) {
    if (command.amount().signum() <= 0) {
      throw new IllegalArgumentException("Payment declined: amount " + command.amount());
    }
    String paymentId = "pay-" + command.orderId();
    charges.put(paymentId, command.amount());
    events.accept(new PaymentProcessed(command.orderId(), paymentId, command.amount()));
  }

  @CommandHandler
  public void handle(RefundPayment command) {
    refunds.putIfAbsent(command.refundKey(), command.amount());
  }

  public Map<String, BigDecimal> charges() {
    return Map.copyOf(charges);
  }

  public Map<String, BigDecimal> refunds() {
    return Map.copyOf(refunds);
  }
}
