package io.github.gkosharovdev.sagaforge.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.UndeclaredThrowableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link FailureClassifier} (specs/005-step-retry contracts/retry-policy.md "Classification", tasks.md T036). */
class FailureClassifierTest {

    private static final class Transient extends RuntimeException implements TransientFailure {
    }

    private static final class Final extends RuntimeException implements FinalFailure {
    }

    private static final class Both extends RuntimeException implements TransientFailure, FinalFailure {
    }

    private static final class Looping extends RuntimeException {

        private Throwable cause;

        @Override
        public synchronized Throwable getCause() {
            return cause;
        }
    }

    @SafeVarargs
    private static RetryPolicy retryOn(Class<? extends Throwable>... types) {
        return new RetryPolicy() {
            @Override
            public int maxAttempts() {
                return 3;
            }

            @Override
            public long initialBackoffMillis() {
                return 0;
            }

            @Override
            public double multiplier() {
                return 1.0;
            }

            @Override
            public long maxBackoffMillis() {
                return 0;
            }

            @Override
            public Class<? extends Throwable>[] retryOn() {
                return types;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return RetryPolicy.class;
            }
        };
    }

    @Test
    @DisplayName("markers decide; final wins on one element and over retryOn")
    void markers() {
        assertThat(FailureClassifier.classify(new Transient(), retryOn())).isEqualTo(FailureClassification.TRANSIENT);
        assertThat(FailureClassifier.classify(new Final(), retryOn(Final.class)))
                .isEqualTo(FailureClassification.FINAL);
        assertThat(FailureClassifier.classify(new Both(), retryOn())).isEqualTo(FailureClassification.FINAL);
    }

    @Test
    @DisplayName("a marker on a wrapped cause counts, the outermost marker first")
    void wrapped() {
        assertThat(FailureClassifier.classify(new UndeclaredThrowableException(new Transient()), retryOn()))
                .isEqualTo(FailureClassification.TRANSIENT);
        RuntimeException finalOverTransient = new IllegalStateException(new Final());
        finalOverTransient.getCause().initCause(new Transient());
        assertThat(FailureClassifier.classify(finalOverTransient, retryOn())).isEqualTo(FailureClassification.FINAL);
    }

    @Test
    @DisplayName("without a marker, a retryOn type anywhere in the chain is transient; otherwise unclassified")
    void retryOnTypes() {
        assertThat(FailureClassifier.classify(new UncheckedIOException(new IOException("reset")),
                retryOn(IOException.class))).isEqualTo(FailureClassification.TRANSIENT);
        assertThat(FailureClassifier.classify(new IllegalStateException("bug"), retryOn(IOException.class)))
                .isEqualTo(FailureClassification.UNCLASSIFIED);
        assertThat(FailureClassifier.classify(new IllegalStateException("bug"), retryOn()))
                .isEqualTo(FailureClassification.UNCLASSIFIED);
    }

    @Test
    @DisplayName("an Error is unclassified whatever retryOn lists")
    void error() {
        assertThat(FailureClassifier.classify(new StackOverflowError(), retryOn(Throwable.class)))
                .isEqualTo(FailureClassification.UNCLASSIFIED);
    }

    @Test
    @DisplayName("a cause cycle ends the walk")
    void cycle() {
        Looping first = new Looping();
        Looping second = new Looping();
        first.cause = second;
        second.cause = first;

        assertThat(FailureClassifier.classify(first, retryOn())).isEqualTo(FailureClassification.UNCLASSIFIED);
    }
}
