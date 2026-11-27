package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import org.jmolecules.architecture.cqrs.CommandHandler;
import org.jmolecules.ddd.types.AggregateRoot;

/**
 * The order, an {@code AggregateRoot<Order, OrderId>} identified by {@code getId()}; reserving its
 * stock records {@link OrderCompleted}, or {@link StockUnavailable} when it is out of stock.
 */
public final class Order implements AggregateRoot<Order, OrderId> {

  private final OrderId id;
  private final boolean inStock;
  private boolean reserved;

  public Order(OrderId id, boolean inStock) {
    this.id = id;
    this.inStock = inStock;
  }

  @Override
  public OrderId getId() {
    return id;
  }

  public boolean isReserved() {
    return reserved;
  }

  @CommandHandler
  public void handle(ReserveStock command) {
    if (inStock) {
      reserved = true;
      DomainEvents.record(new OrderCompleted(id));
    } else {
      DomainEvents.record(new StockUnavailable(id));
    }
  }
}
