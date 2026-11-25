package io.github.gkosharovdev.sagaforge.core.failure;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * How the engine classifies the failure that rolled back a saga-scoped unit of work
 * ({@code specs/005-step-retry} FR-008).
 *
 * <p>Only {@link #TRANSIENT} failures are retried. A persisted {@code FAILED} instance never carries
 * {@code TRANSIENT}: a transient failure on the last allowed attempt is recorded as {@link #EXHAUSTED}.</p>
 */
public enum FailureClassification {

    /** May succeed on a later attempt: marked {@link TransientFailure}, or listed in the step's {@code retryOn}. */
    TRANSIENT,

    /** Declared final by the author through {@link FinalFailure}; never retried. */
    FINAL,

    /** Transient, but the step's retry policy allowed no further attempt. */
    EXHAUSTED,

    /**
     * Neither marked nor listed as retryable (a programming error, for example, or any {@link Error}); never
     * retried. Kept apart from {@link #FINAL} so that later features, such as compensation, can react only to
     * failures an author chose to treat as final.
     */
    UNCLASSIFIED;

    /**
     * Classifies {@code failure} by its markers alone, the rule the engine and transports share
     * ({@code specs/012-outbox-command-failure-feedback} research §3).
     *
     * <p>An {@link Error} is {@link #UNCLASSIFIED}. Otherwise the cause chain is walked from {@code failure},
     * stopping at a cause already seen: the first element implementing {@link FinalFailure} gives {@link #FINAL},
     * the first implementing {@link TransientFailure} gives {@link #TRANSIENT}, and one implementing both is final.
     * Without a marker the failure is {@link #UNCLASSIFIED}. The result is never {@link #EXHAUSTED}: exhaustion is
     * decided by counting attempts.</p>
     *
     * @param failure the failure to classify; must not be {@code null}
     * @return {@link #FINAL}, {@link #TRANSIENT} or {@link #UNCLASSIFIED}
     * @throws IllegalArgumentException if {@code failure} is {@code null}
     */
    public static FailureClassification of(Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        if (failure instanceof Error) {
            return UNCLASSIFIED;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable element = failure; element != null && seen.add(element); element = element.getCause()) {
            if (element instanceof FinalFailure) {
                return FINAL;
            }
            if (element instanceof TransientFailure) {
                return TRANSIENT;
            }
        }
        return UNCLASSIFIED;
    }
}
