package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;

/** Client-owned, in-memory persistence of {@link Order}; a bean. */
public class OrderRepository {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    @AggregateLoader
    public Order load(String orderId) {
        return orders.get(orderId);
    }

    @AggregateSaver
    public void save(Order order) {
        orders.put(order.orderId(), order);
    }
}
