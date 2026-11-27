package io.github.gkosharovdev.sagaforge.example.jmolecules.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jmolecules.architecture.cqrs.CommandHandler;
import org.jmolecules.ddd.types.AggregateRoot;

/**
 * An order: reserving its stock completes it, or reports the shortage. Events wait in the aggregate
 * until its repository takes them ({@link #domainEvents()}, {@link #clearDomainEvents()}).
 */
public final class Order implements AggregateRoot<Order, OrderId> {

  /** Where the order stands. */
  public enum Status {
    PLACED,
    COMPLETED,
    OUT_OF_STOCK
  }

  private final OrderId id;
  private final boolean inStock;
  private Status status;
  private final List<Object> events = new ArrayList<>();

  private Order(OrderId id, boolean inStock, Status status) {
    this.id = Objects.requireNonNull(id, "id");
    this.inStock = inStock;
    this.status = Objects.requireNonNull(status, "status");
  }

  /** A new order, not yet reserved. */
  public static Order place(OrderId id, boolean inStock) {
    return new Order(id, inStock, Status.PLACED);
  }

  /** An order as its repository stored it. */
  public static Order restore(OrderId id, boolean inStock, Status status) {
    return new Order(id, inStock, status);
  }

  @CommandHandler
  public void handle(ReserveStock command) {
    if (inStock) {
      status = Status.COMPLETED;
      events.add(new OrderCompleted(id));
    } else {
      status = Status.OUT_OF_STOCK;
      events.add(new StockUnavailable(id));
    }
  }

  @Override
  public OrderId getId() {
    return id;
  }

  public boolean isInStock() {
    return inStock;
  }

  public Status status() {
    return status;
  }

  /** The events recorded since the repository last took them, oldest first. */
  public List<Object> domainEvents() {
    return List.copyOf(events);
  }

  public void clearDomainEvents() {
    events.clear();
  }
}
