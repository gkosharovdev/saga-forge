package io.github.gkosharovdev.sagaforge.test;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Makes matching commands fail ({@code specs/009-saga-test-fixture} FR-008): a command type, an optional content
 * match, the exception to throw, and how many matching handlings fail. Classification is the runtime's.
 */
final class CommandFailureRule {

    static final int ALWAYS = -1;

    private final Class<?> commandType;
    private final Predicate<Object> matcher;
    private final Supplier<? extends RuntimeException> failure;
    private int remaining;

    <T> CommandFailureRule(Class<T> commandType, Predicate<? super T> matcher,
            Supplier<? extends RuntimeException> failure, int times) {
        if (commandType == null || matcher == null || failure == null) {
            throw new IllegalArgumentException("commandType, matcher and failure must not be null");
        }
        if (times < 1 && times != ALWAYS) {
            throw new IllegalArgumentException("times must be at least 1, but is " + times);
        }
        this.commandType = commandType;
        this.matcher = command -> matcher.test(commandType.cast(command));
        this.failure = failure;
        this.remaining = times;
    }

    synchronized boolean matches(Object command) {
        return remaining != 0 && commandType.isInstance(command) && matcher.test(command);
    }

    /** The exception to throw for one matching command; counts it. */
    synchronized RuntimeException fail() {
        if (remaining > 0) {
            remaining--;
        }
        RuntimeException thrown = failure.get();
        if (thrown == null) {
            throw new IllegalStateException("the failure supplier for " + commandType.getName() + " returned null");
        }
        return thrown;
    }
}
