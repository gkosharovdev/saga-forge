package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Classifies the failure that rolled a unit of work back (005 contracts/retry-policy.md "Classification").
 *
 * <ol>
 *   <li>The markers decide, as {@link FailureClassification#of(Throwable)} reads them: an {@link Error} is
 *       {@code UNCLASSIFIED}, otherwise the first {@code FinalFailure} or {@code TransientFailure} along the cause
 *       chain.</li>
 *   <li>Without a marker, an element that is an instance of a {@code retryOn} type of the failing step's policy
 *       gives {@code TRANSIENT}.</li>
 *   <li>Otherwise {@code UNCLASSIFIED}.</li>
 * </ol>
 * <p>The result is never {@code EXHAUSTED}; the attempt loop records a transient failure on the last attempt so.</p>
 */
final class FailureClassifier {

    private FailureClassifier() {
    }

    static FailureClassification classify(Throwable failure, RetryPolicy policy) {
        FailureClassification marked = FailureClassification.of(failure);
        if (marked != FailureClassification.UNCLASSIFIED || failure instanceof Error) {
            return marked;
        }
        for (Throwable element : causeChain(failure)) {
            for (Class<? extends Throwable> retryable : policy.retryOn()) {
                if (retryable.isInstance(element)) {
                    return FailureClassification.TRANSIENT;
                }
            }
        }
        return FailureClassification.UNCLASSIFIED;
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable element = failure; element != null && seen.add(element); element = element.getCause()) {
            chain.add(element);
        }
        return chain;
    }
}
