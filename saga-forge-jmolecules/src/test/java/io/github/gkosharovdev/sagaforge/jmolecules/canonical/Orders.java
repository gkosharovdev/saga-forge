package io.github.gkosharovdev.sagaforge.jmolecules.canonical;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import java.util.HashMap;
import java.util.Map;

/** The application's repository: Saga Forge loader and saver over typed identifiers. */
public final class Orders {

  private final Map<OrderId, Order> orders = new HashMap<>();

  public Orders add(Order order) {
    orders.put(order.getId(), order);
    return this;
  }

  @AggregateLoader
  public Order load(OrderId id) {
    return orders.get(id);
  }

  @AggregateSaver
  public void save(Order order) {
    orders.put(order.getId(), order);
  }
}
