package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent2;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rule R19 / Property 29: {@code @SagaStep.retry} policy validity (specs/005-step-retry FR-012,
 * {@code .kiro/specs/saga-forge-framework/design.md}, tasks.md T034).
 */
class RetryPolicyValidationTest {

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    @Saga
    static class ValidRetrySaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 200,
                multiplier = 2.0, maxBackoffMillis = 1000, retryOn = IOException.class))
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent2 event) {
        }
    }

    @Saga
    static class InvalidRetrySaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 0, initialBackoffMillis = -1,
                multiplier = 0.5, retryOn = OutOfMemoryError.class))
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 2, initialBackoffMillis = 500,
                maxBackoffMillis = 100))
        public void end(TestEvent2 event) {
        }
    }

    @Saga
    static class SingleAttemptWithSettingsSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(initialBackoffMillis = 10, multiplier = 2.0,
                maxBackoffMillis = 20, retryOn = IOException.class))
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent2 event) {
        }
    }

    @Test
    @DisplayName("a valid policy and the default policy produce no error")
    void valid() {
        assertThat(scanner.validate(ValidRetrySaga.class).errors()).isEmpty();
        assertThat(scanner.validateAll(List.of(ValidRetrySaga.class)).isValid()).isTrue();
    }

    @Test
    @DisplayName("each violated rule is one error, steps in name order")
    void invalid() {
        String end = "@SagaStep method end in InvalidRetrySaga has an invalid retry policy: ";
        String start = "@SagaStep method start in InvalidRetrySaga has an invalid retry policy: ";

        assertThat(scanner.validate(InvalidRetrySaga.class).errors()).containsExactly(
                end + "maxBackoffMillis must be 0 or at least initialBackoffMillis 500, found 100",
                start + "maxAttempts must be at least 1, found 0",
                start + "initialBackoffMillis must not be negative, found -1",
                start + "multiplier must be a finite number of at least 1.0, found 0.5",
                start + "retryOn must not name Error type OutOfMemoryError");
        assertThat(scanner.validateAll(List.of(InvalidRetrySaga.class)).classResults().get(0).errors())
                .hasSize(5);
    }

    @Test
    @DisplayName("with maxAttempts 1 every other attribute must keep its default")
    void singleAttemptKeepsDefaults() {
        String start = "@SagaStep method start in SingleAttemptWithSettingsSaga has an invalid retry policy: ";

        assertThat(scanner.validate(SingleAttemptWithSettingsSaga.class).errors()).containsExactly(
                start + "initialBackoffMillis must keep its default when maxAttempts is 1",
                start + "multiplier must keep its default when maxAttempts is 1",
                start + "maxBackoffMillis must keep its default when maxAttempts is 1",
                start + "retryOn must keep its default when maxAttempts is 1");
    }

    /** A {@link RetryPolicy} built from values, so the property below can generate them. */
    private record Policy(int maxAttempts, long initialBackoffMillis, double multiplier, long maxBackoffMillis,
            Class<? extends Throwable>[] retryOn) implements RetryPolicy {

        @Override
        public Class<? extends Annotation> annotationType() {
            return RetryPolicy.class;
        }
    }

    /** Property 29: R19 errors appear exactly for the violated rules. */
    @Property(tries = 300)
    void retryPolicyValidity(@ForAll @IntRange(min = -2, max = 6) int maxAttempts,
            @ForAll @LongRange(min = -5, max = 50) long initial,
            @ForAll @DoubleRange(min = 0.0, max = 3.0) double multiplier,
            @ForAll @LongRange(min = 0, max = 60) long cap,
            @ForAll boolean withError, @ForAll boolean withRetryable) {
        List<Class<? extends Throwable>> types = new ArrayList<>();
        if (withError) {
            types.add(StackOverflowError.class);
        }
        if (withRetryable) {
            types.add(IOException.class);
        }
        @SuppressWarnings("unchecked")
        Class<? extends Throwable>[] retryOn = types.toArray(new Class[0]);
        Policy policy = new Policy(maxAttempts, initial, multiplier, cap, retryOn);

        List<String> attributes = ReflectiveAnnotationScanner.retryPolicyViolations(policy).stream()
                .map(violation -> violation.substring(0, violation.indexOf(' '))).toList();

        List<String> expected = new ArrayList<>();
        if (maxAttempts < 1) {
            expected.add("maxAttempts");
        }
        if (initial < 0) {
            expected.add("initialBackoffMillis");
        }
        if (multiplier < 1.0) {
            expected.add("multiplier");
        }
        if (cap != 0 && cap < initial) {
            expected.add("maxBackoffMillis");
        }
        if (withError) {
            expected.add("retryOn");
        }
        if (maxAttempts == 1) {
            if (initial != 0) {
                expected.add("initialBackoffMillis");
            }
            if (multiplier != 1.0) {
                expected.add("multiplier");
            }
            if (cap != 0) {
                expected.add("maxBackoffMillis");
            }
            if (retryOn.length != 0) {
                expected.add("retryOn");
            }
        }
        assertThat(attributes).containsExactlyElementsOf(expected);
    }
}
