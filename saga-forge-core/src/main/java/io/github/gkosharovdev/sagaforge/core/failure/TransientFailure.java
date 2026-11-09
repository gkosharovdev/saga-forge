package io.github.gkosharovdev.sagaforge.core.failure;

/**
 * Marks a failure that may succeed if the step is run again: a timeout, a dropped connection, a temporarily
 * unavailable service.
 *
 * <p>Implemented by an exception class; no base class is required. When a step's unit of work fails and its
 * failure, or any cause in its chain, implements this interface, the engine treats the failure as
 * {@link FailureClassification#TRANSIENT} and runs the unit of work again while the step's
 * {@code @SagaStep(retry = @RetryPolicy(...))} allows further attempts. An exception class that implements both
 * this interface and {@link FinalFailure} is treated as final.</p>
 *
 * @see FailureClassification
 */
public interface TransientFailure {
}
