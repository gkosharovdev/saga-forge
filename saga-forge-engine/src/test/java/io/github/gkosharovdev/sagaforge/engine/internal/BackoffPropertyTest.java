package io.github.gkosharovdev.sagaforge.engine.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import java.lang.annotation.Annotation;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/** {@link Backoff} arithmetic (specs/005-step-retry contracts/retry-policy.md, tasks.md T035). */
class BackoffPropertyTest {

    private record Policy(int maxAttempts, long initialBackoffMillis, double multiplier, long maxBackoffMillis)
            implements RetryPolicy {

        @Override
        public Class<? extends Throwable>[] retryOn() {
            return new Class[0];
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return RetryPolicy.class;
        }
    }

    @Property
    void waitsAreNonDecreasingCappedAndSumToTheTotal(@ForAll @IntRange(min = 2, max = 12) int maxAttempts,
            @ForAll @LongRange(min = 0, max = 5_000) long initial, @ForAll @DoubleRange(min = 1.0, max = 4.0) double
            multiplier, @ForAll @LongRange(min = 0, max = 20_000) long extraCap) {
        long cap = extraCap == 0 ? 0 : initial + extraCap;
        Policy policy = new Policy(maxAttempts, initial, multiplier, cap);
        long effectiveCap = cap == 0 ? initial : cap;

        long previous = -1;
        long sum = 0;
        for (int attempt = 2; attempt <= maxAttempts; attempt++) {
            long wait = Backoff.waitBefore(policy, attempt);
            assertThat(wait).isBetween(Math.min(initial, effectiveCap), effectiveCap).isGreaterThanOrEqualTo(previous);
            previous = wait;
            sum += wait;
        }
        assertThat(Backoff.total(policy)).isEqualTo(sum);
    }

    @Property
    void aFixedPolicyWaitsInitialBetweenEveryAttempt(@ForAll @IntRange(min = 1, max = 20) int maxAttempts,
            @ForAll @LongRange(min = 0, max = 10_000) long initial) {
        assertThat(Backoff.total(new Policy(maxAttempts, initial, 1.0, 0))).isEqualTo((maxAttempts - 1) * initial);
    }

    @Test
    void theContractExampleWaits200Then400ThenIsCapped() {
        Policy policy = new Policy(5, 200, 2.0, 1_000);

        assertThat(Backoff.waitBefore(policy, 2)).isEqualTo(200);
        assertThat(Backoff.waitBefore(policy, 3)).isEqualTo(400);
        assertThat(Backoff.waitBefore(policy, 4)).isEqualTo(800);
        assertThat(Backoff.waitBefore(policy, 5)).isEqualTo(1_000);
        assertThat(Backoff.total(policy)).isEqualTo(2_400);
    }

    @Test
    void aHugeTotalSaturatesInsteadOfOverflowing() {
        assertThat(Backoff.total(new Policy(Integer.MAX_VALUE, Long.MAX_VALUE / 2, 1.0, 0)))
                .isEqualTo(Long.MAX_VALUE);
    }
}
