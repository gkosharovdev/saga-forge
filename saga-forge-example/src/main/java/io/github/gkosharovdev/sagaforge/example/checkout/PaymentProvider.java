package io.github.gkosharovdev.sagaforge.example.checkout;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A stand-in for an outside card payment provider that honours idempotency keys: a call repeated with a key it has
 * seen changes nothing. It authorises (holds) an amount at checkout, captures (takes) it at dispatch, and voids an
 * authorisation or refunds a capture when the order is not delivered.
 */
public class PaymentProvider {

    /** Where a payment stands at the provider. */
    public enum State { AUTHORIZED, CAPTURED, VOIDED, REFUNDED }

    private final Map<String, String> paymentsByKey = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, BigDecimal> amounts = new LinkedHashMap<>();
    private int timeoutsLeft;
    private String declineReason;

    /**
     * Holds {@code amount} once per {@code idempotencyKey}.
     *
     * @return the payment id, or empty if the card was declined
     * @throws PaymentProviderTimeoutException if a timeout was {@linkplain #timeOutNext(int) scripted}
     */
    public Optional<String> authorize(String idempotencyKey, BigDecimal amount) {
        if (timeoutsLeft > 0) {
            timeoutsLeft--;
            throw new PaymentProviderTimeoutException("no answer for key " + idempotencyKey);
        }
        if (declineReason != null) {
            return Optional.empty();
        }
        String paymentId = paymentsByKey.computeIfAbsent(idempotencyKey, key -> "payment-" + key);
        states.putIfAbsent(paymentId, State.AUTHORIZED);
        amounts.putIfAbsent(paymentId, amount);
        return Optional.of(paymentId);
    }

    public void capture(String paymentId) {
        states.computeIfPresent(paymentId, (id, state) -> state == State.AUTHORIZED ? State.CAPTURED : state);
    }

    public void voidAuthorization(String paymentId) {
        states.computeIfPresent(paymentId, (id, state) -> state == State.AUTHORIZED ? State.VOIDED : state);
    }

    public void refund(String paymentId) {
        states.computeIfPresent(paymentId, (id, state) -> state == State.CAPTURED ? State.REFUNDED : state);
    }

    public State state(String paymentId) {
        return states.get(paymentId);
    }

    /** Makes the next {@code calls} authorisations time out. */
    public void timeOutNext(int calls) {
        this.timeoutsLeft = calls;
    }

    /** Makes every later authorisation decline with {@code reason}. */
    public void declineWith(String reason) {
        this.declineReason = reason;
    }

    public String declineReason() {
        return declineReason;
    }

    /** The amount authorised per payment id. */
    public Map<String, BigDecimal> authorizations() {
        return Map.copyOf(amounts);
    }
}
