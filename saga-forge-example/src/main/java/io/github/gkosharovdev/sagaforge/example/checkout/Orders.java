package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-owned persistence for {@link Order}, kept in memory for the example. In a Spring Boot
 * application a Spring Data repository takes its place.
 */
public class Orders {

  private final Map<String, Order> orders = new ConcurrentHashMap<>();

  @AggregateLoader
  public Order load(String orderId) {
    return orders.get(orderId);
  }

  @AggregateSaver
  public void save(Order order) {
    orders.put(order.orderId(), order);
  }

  public Optional<Order> find(String orderId) {
    return Optional.ofNullable(orders.get(orderId));
  }
}
