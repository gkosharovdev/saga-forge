package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A payment provider; the application has two, so a handler parameter must name one with {@code @Qualifier}. */
public class PaymentProvider {

    private final String name;
    private final List<String> charged = new CopyOnWriteArrayList<>();

    public PaymentProvider(String name) {
        this.name = name;
    }

    public String charge(String key) {
        charged.add(key);
        return name + "-" + key;
    }

    public String name() {
        return name;
    }

    public List<String> charged() {
        return List.copyOf(charged);
    }
}
