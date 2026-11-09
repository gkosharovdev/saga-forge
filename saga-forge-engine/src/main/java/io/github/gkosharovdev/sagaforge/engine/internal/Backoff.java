package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;

/**
 * Wait arithmetic of a {@link RetryPolicy} (005 contracts/retry-policy.md "Wait before attempt k"). Saturates at
 * {@link Long#MAX_VALUE} instead of overflowing.
 */
final class Backoff {

    private Backoff() {
    }

    /**
     * @param policy  a policy the scanner accepted
     * @param attempt the attempt about to run; at least 2
     * @return milliseconds to wait before {@code attempt}: {@code min(initial * multiplier^(attempt-2), cap)}
     */
    static long waitBefore(RetryPolicy policy, int attempt) {
        if (attempt < 2) {
            throw new IllegalArgumentException("attempt must be at least 2, but is " + attempt);
        }
        long cap = policy.maxBackoffMillis() == 0 ? policy.initialBackoffMillis() : policy.maxBackoffMillis();
        double wait = policy.initialBackoffMillis() * Math.pow(policy.multiplier(), attempt - 2);
        return wait >= cap ? cap : (long) wait;
    }

    /**
     * @param policy a policy the scanner accepted
     * @return milliseconds the policy waits over all its attempts: the sum of {@link #waitBefore} for attempts 2 to
     *         {@code maxAttempts}
     */
    static long total(RetryPolicy policy) {
        long total = 0;
        for (int attempt = 2; attempt <= policy.maxAttempts(); attempt++) {
            long wait = waitBefore(policy, attempt);
            if (Long.MAX_VALUE - total < wait) {
                return Long.MAX_VALUE;
            }
            total += wait;
        }
        return total;
    }
}
