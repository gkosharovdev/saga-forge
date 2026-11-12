package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;

/**
 * The retry the engine has claimed for an instance's current unit of work ({@code specs/005-step-retry} FR-015).
 *
 * <p>Written in its own transaction after a transient failure, before the engine waits for the next attempt, and
 * again when a redelivery resumes the retry, before that attempt runs, so a restart never grants more attempts than
 * the policy allows. Cleared when a unit of work triggered by
 * {@code triggerStep} commits.</p>
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component violates the rules above.</p>
 *
 * @param triggerStep     the step the triggering event runs; non-blank
 * @param attemptsStarted the attempt number claimed so far; at least 2
 * @param lastFailure     the last recorded failure, classified {@link FailureClassification#TRANSIENT}; of attempt
 *                        {@code attemptsStarted - 1}, or of an earlier attempt when a redelivery claimed the next one
 *                        after a crash ({@code lastFailure.attempts() < attemptsStarted})
 */
public record PendingRetry(String triggerStep, int attemptsStarted, SagaFailure lastFailure) {

    public PendingRetry {
        if (triggerStep == null || triggerStep.isBlank()) {
            throw new IllegalArgumentException("triggerStep must not be null or blank");
        }
        if (attemptsStarted < 2) {
            throw new IllegalArgumentException("attemptsStarted must be at least 2, but is " + attemptsStarted);
        }
        if (lastFailure == null) {
            throw new IllegalArgumentException("lastFailure must not be null");
        }
        if (lastFailure.classification() != FailureClassification.TRANSIENT) {
            throw new IllegalArgumentException("lastFailure must be TRANSIENT, but is "
                    + lastFailure.classification());
        }
        if (lastFailure.attempts() >= attemptsStarted) {
            throw new IllegalArgumentException("lastFailure.attempts must be below attemptsStarted "
                    + attemptsStarted + ", but is " + lastFailure.attempts());
        }
    }
}
