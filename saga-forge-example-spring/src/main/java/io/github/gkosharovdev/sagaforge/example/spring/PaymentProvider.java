package io.github.gkosharovdev.sagaforge.example.spring;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * A stand-in for an outside payment provider that honours idempotency keys: a repeated key changes nothing. It is not
 * part of the saga's transaction, which is why {@link PaymentService} passes the command identity as the key.
 */
@Component
public class PaymentProvider {

    private final Map<String, String> paymentsByKey = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> charges = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> refunds = new ConcurrentHashMap<>();

    public String charge(String idempotencyKey, BigDecimal amount) {
        String paymentId = paymentsByKey.computeIfAbsent(idempotencyKey, key -> "payment-" + key);
        charges.putIfAbsent(paymentId, amount);
        return paymentId;
    }

    public void refund(String paymentId, BigDecimal amount) {
        refunds.putIfAbsent(paymentId, amount);
    }

    public Map<String, BigDecimal> charges() {
        return Map.copyOf(charges);
    }

    public Map<String, BigDecimal> refunds() {
        return Map.copyOf(refunds);
    }
}
