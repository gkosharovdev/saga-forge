package io.github.gkosharovdev.sagaforge.core.failure;

/**
 * Marks a failure that running the step again cannot fix: a domain rule violation, a rejected request.
 *
 * <p>Implemented by an exception class; no base class is required. When a step's unit of work fails and its
 * failure, or any cause in its chain, implements this interface, the engine never retries it and records the
 * saga {@code FAILED} with {@link FailureClassification#FINAL}, whatever the step's retry policy says. This marker
 * wins over {@link TransientFailure} and over the policy's {@code retryOn} types.</p>
 *
 * @see FailureClassification
 */
public interface FinalFailure {
}
