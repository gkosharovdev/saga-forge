package io.github.gkosharovdev.sagaforge.example;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;

/**
 * Client-owned persistence for {@link Order}, kept in memory for the example. Saga Forge calls the
 * {@code @AggregateLoader} before an {@code Order} instance-method handler runs and the {@code @AggregateSaver}
 * after it returns; no Saga Forge interface is implemented.
 */
public class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    /**
     * Loads an order by id. Returns {@code null} when there is none, which the engine reports as an
     * {@code AggregateNotFoundException} and fails the unit of work.
     */
    @AggregateLoader
    public Order load(String orderId) {
        return orders.get(orderId);
    }

    @AggregateSaver
    public void save(Order order) {
        orders.put(order.orderId(), order);
    }
}
