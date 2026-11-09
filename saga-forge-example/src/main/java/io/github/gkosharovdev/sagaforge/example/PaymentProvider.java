package io.github.gkosharovdev.sagaforge.example;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stand-in for an outside payment provider that honours idempotency keys: charging again with a key it has seen
 * returns the first payment instead of charging twice. It is not part of the saga's transaction, so a rollback does
 * not undo a charge, which is why {@link PaymentService} passes the command's identity as the key.
 */
public class PaymentProvider {

    private final Map<String, String> paymentsByKey = new LinkedHashMap<>();
    private final Map<String, BigDecimal> amounts = new LinkedHashMap<>();
    private int timeoutsLeft;

    /**
     * Charges {@code amount} once per {@code idempotencyKey}.
     *
     * @return the payment id, the same for every call with the same key
     * @throws PaymentGatewayTimeoutException if a timeout was {@linkplain #timeOutNext(int) scripted}; the charge
     *                                        was taken before the answer was lost
     */
    public String charge(String idempotencyKey, BigDecimal amount) {
        String paymentId = paymentsByKey.computeIfAbsent(idempotencyKey, key -> "payment-" + key);
        amounts.putIfAbsent(paymentId, amount);
        if (timeoutsLeft > 0) {
            timeoutsLeft--;
            throw new PaymentGatewayTimeoutException("payment provider did not answer for key " + idempotencyKey);
        }
        return paymentId;
    }

    /** Makes the next {@code calls} charges time out after charging. */
    public void timeOutNext(int calls) {
        this.timeoutsLeft = calls;
    }

    /** The amount charged per payment id. */
    public Map<String, BigDecimal> charges() {
        return Map.copyOf(amounts);
    }
}
