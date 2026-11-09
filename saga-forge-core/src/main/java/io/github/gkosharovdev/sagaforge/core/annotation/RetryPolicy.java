package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How often the engine may run a step's unit of work and how long it waits between attempts. Used only as the
 * value of {@link SagaStep#retry()}.
 *
 * <p>The wait before attempt {@code k >= 2} is {@code min(initialBackoffMillis * multiplier^(k-2), cap)}, where
 * {@code cap} is {@link #maxBackoffMillis()} or, when that is {@code 0}, {@link #initialBackoffMillis()}. The engine
 * waits in-process and rejects at startup a policy whose total wait exceeds its configured maximum.</p>
 *
 * <p>Only transient failures are retried: a failure, or a cause in its chain, that implements
 * {@code core.failure.TransientFailure} or is an instance of a {@link #retryOn()} type, and implements no
 * {@code core.failure.FinalFailure}. The scanner validates the values (rule R19, Property 29 in
 * {@code .kiro/specs/saga-forge-framework/design.md}).</p>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RetryPolicy {

    /**
     * @return attempts including the first; at least 1. The default, 1, means no retry
     */
    int maxAttempts() default 1;

    /**
     * @return milliseconds to wait before attempt 2; not negative
     */
    long initialBackoffMillis() default 0;

    /**
     * @return factor applied to the wait for each further attempt; finite and at least 1.0 (1.0 is a fixed wait)
     */
    double multiplier() default 1.0;

    /**
     * @return cap on a single wait in milliseconds; 0 (the default) means the same as
     *         {@link #initialBackoffMillis()}, otherwise at least {@link #initialBackoffMillis()}
     */
    long maxBackoffMillis() default 0;

    /**
     * @return unmarked failure types treated as transient; must not name an {@link Error} type
     */
    Class<? extends Throwable>[] retryOn() default {};
}
