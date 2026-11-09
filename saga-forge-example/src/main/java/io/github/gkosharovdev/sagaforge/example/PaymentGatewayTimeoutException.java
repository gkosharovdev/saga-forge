package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;

/**
 * The payment provider did not answer in time. Marked {@link TransientFailure}: running the step again may succeed,
 * so the engine retries it under the step's {@code @RetryPolicy}.
 */
public class PaymentGatewayTimeoutException extends RuntimeException implements TransientFailure {

    public PaymentGatewayTimeoutException(String message) {
        super(message);
    }
}
