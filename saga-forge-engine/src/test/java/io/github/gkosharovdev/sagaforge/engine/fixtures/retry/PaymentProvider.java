package io.github.gkosharovdev.sagaforge.engine.fixtures.retry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An outside, non-transactional payment provider that charges once per idempotency key. */
public final class PaymentProvider {

    private final Map<String, Long> charges = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();

    public void charge(String idempotencyKey, long amount) {
        calls.add(idempotencyKey);
        Long existing = charges.putIfAbsent(idempotencyKey, amount);
        if (existing != null && existing != amount) {
            throw new IllegalStateException("Key " + idempotencyKey + " was used for " + existing);
        }
    }

    public Map<String, Long> charges() {
        return Map.copyOf(charges);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }
}
