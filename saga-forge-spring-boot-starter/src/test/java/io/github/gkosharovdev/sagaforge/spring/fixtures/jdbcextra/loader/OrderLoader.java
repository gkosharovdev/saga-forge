package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.loader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrder;

/** A client loader and saver for {@link JdbcOrder}: they take precedence over its Spring Data repository. */
public class OrderLoader {

    private final Map<String, JdbcOrder> orders = new ConcurrentHashMap<>();
    private final AtomicInteger loads = new AtomicInteger();

    public void put(JdbcOrder order) {
        orders.put(order.orderId(), order);
    }

    @AggregateLoader
    public JdbcOrder load(String orderId) {
        loads.incrementAndGet();
        return orders.get(orderId);
    }

    @AggregateSaver
    public void save(JdbcOrder order) {
        orders.put(order.orderId(), order);
    }

    public int loads() {
        return loads.get();
    }

    public JdbcOrder get(String orderId) {
        return orders.get(orderId);
    }

    /** Adds the loader bean; the package is added through {@code saga-forge.scan-packages}. */
    @Configuration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        OrderLoader orderLoader() {
            return new OrderLoader();
        }
    }
}
