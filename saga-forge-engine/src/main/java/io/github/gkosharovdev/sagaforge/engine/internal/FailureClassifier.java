package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Classifies the failure that rolled a unit of work back (005 contracts/retry-policy.md "Classification").
 *
 * <ol>
 *   <li>An {@link Error} is {@code UNCLASSIFIED}.</li>
 *   <li>Along the cause chain (stopping at a repeat), the first element implementing {@link FinalFailure} gives
 *       {@code FINAL}, the first implementing {@link TransientFailure} gives {@code TRANSIENT}; one implementing both
 *       is final.</li>
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
        if (failure instanceof Error) {
            return FailureClassification.UNCLASSIFIED;
        }
        List<Throwable> chain = causeChain(failure);
        for (Throwable element : chain) {
            if (element instanceof FinalFailure) {
                return FailureClassification.FINAL;
            }
            if (element instanceof TransientFailure) {
                return FailureClassification.TRANSIENT;
            }
        }
        for (Throwable element : chain) {
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
