package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An outside payment provider: not a transaction participant, so a rollback does not undo a charge. Charges are
 * keyed by idempotency key: a repeated key with the same amount returns the first charge, a repeated key with a
 * different amount is refused.
 */
public final class FakePaymentProvider {

    private final Map<String, Long> charges = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();

    /** Charges {@code amount} once per {@code idempotencyKey}; returns the charge reference. */
    public String charge(String idempotencyKey, long amount) {
        calls.add(idempotencyKey);
        Long existing = charges.putIfAbsent(idempotencyKey, amount);
        if (existing != null && existing != amount) {
            throw new IllegalStateException("Key " + idempotencyKey + " was used for " + existing + ", not " + amount);
        }
        return "charge-" + idempotencyKey;
    }

    /** Charges by idempotency key, in first-charge order. */
    public Map<String, Long> charges() {
        return Map.copyOf(charges);
    }

    /** Every key passed to {@link #charge}, including repeats. */
    public List<String> calls() {
        return List.copyOf(calls);
    }
}
