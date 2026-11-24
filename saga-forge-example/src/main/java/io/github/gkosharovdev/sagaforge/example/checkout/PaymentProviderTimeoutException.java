package io.github.gkosharovdev.sagaforge.example.checkout;

import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;

/** The payment provider did not answer in time. Transient: the step's {@code @RetryPolicy} runs it again. */
public class PaymentProviderTimeoutException extends RuntimeException implements TransientFailure {

    public PaymentProviderTimeoutException(String message) {
        super(message);
    }
}
