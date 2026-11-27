package io.github.gkosharovdev.sagaforge.example.jmolecules.application;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.Order;
import io.github.gkosharovdev.sagaforge.example.jmolecules.domain.OrderId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Orders in the {@code orders} table, loaded and saved by Saga Forge through the typed {@link
 * OrderId}. The events an order holds reach the saga through {@link OrderEventSource}, taken before
 * the save.
 */
@Repository
public class OrderRepository {

  private final JdbcClient jdbc;

  public OrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Order> findById(OrderId id) {
    return jdbc.sql("SELECT order_id, in_stock, status FROM orders WHERE order_id = ?")
        .param(id.value())
        .query(
            (row, number) ->
                Order.restore(
                    new OrderId(row.getString("order_id")),
                    row.getBoolean("in_stock"),
                    Order.Status.valueOf(row.getString("status"))))
        .optional();
  }

  @AggregateLoader
  public Order load(OrderId id) {
    return findById(id).orElse(null);
  }

  @AggregateSaver
  public void save(Order order) {
    jdbc.sql(
            "INSERT INTO orders (order_id, in_stock, status) VALUES (?, ?, ?)"
                + " ON CONFLICT (order_id) DO UPDATE SET status = EXCLUDED.status")
        .params(order.getId().value(), order.isInStock(), order.status().name())
        .update();
  }
}
