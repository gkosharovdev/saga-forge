package io.github.gkosharovdev.sagaforge.test;

/**
 * A saga did not do what a fixture test expected ({@code specs/009-saga-test-fixture} FR-014). The message names the
 * saga type, the association value, the event under test, the steps that ran and the expected and actual values; an
 * exception that failed the saga is the cause. Any test framework treating an {@link AssertionError} as a failure
 * reports it.
 */
public final class SagaExpectationError extends AssertionError {

    SagaExpectationError(String message) {
        super(message);
    }

    SagaExpectationError(String message, Throwable cause) {
        super(message, cause);
    }
}
