package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Assigns the identities of the commands one saga unit of work's steps send, and binds the identity of the command
 * being handled for the built-in {@code CommandId} handler parameter (005 contracts/command-identity.md).
 *
 * <p>An instance belongs to one unit of work: {@link #startStep(String)} begins a step run and resets the per-type
 * counters, {@link #next(Class)} returns {@code CommandId.of(sagaId, step, type, n)} for the {@code n}-th command of
 * that type sent by this run. Because the identity is a pure function of these components, re-running the unit of
 * work gives every command the identity it had before.</p>
 *
 * <p>The bound identity is a per-thread stack, so a handler that itself sends a command sees the inner identity in
 * the inner handler and its own again afterwards. Not thread-safe apart from the static binding.</p>
 */
final class CommandIds {

    private static final ThreadLocal<Deque<CommandId>> BOUND = ThreadLocal.withInitial(ArrayDeque::new);

    private final String sagaId;
    private final Map<String, Integer> sentPerType = new HashMap<>();
    private String step;

    /**
     * @param sagaId the instance the unit of work runs
     */
    CommandIds(String sagaId) {
        this.sagaId = sagaId;
    }

    /** Begins a run of {@code stepName}: commands sent from now on are counted for it, from 1 per type. */
    void startStep(String stepName) {
        step = stepName;
        sentPerType.clear();
    }

    /**
     * @return the identity of the next command of {@code commandType} sent by the current step run
     * @throws IllegalStateException if no step run has started
     */
    CommandId next(Class<?> commandType) {
        if (step == null) {
            throw new IllegalStateException("No step is running in the unit of work of saga instance " + sagaId);
        }
        int ordinal = sentPerType.merge(commandType.getName(), 1, Integer::sum);
        return CommandId.of(sagaId, step, commandType.getName(), ordinal);
    }

    /**
     * Runs {@code handlerCall} with {@code id} bound as the identity of the command being handled on this thread.
     */
    static <T> T withBound(CommandId id, Supplier<T> handlerCall) {
        Deque<CommandId> stack = BOUND.get();
        stack.push(id);
        try {
            return handlerCall.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) {
                BOUND.remove();
            }
        }
    }

    /** @return the identity of the command being handled on this thread, if a handler call is running */
    static Optional<CommandId> bound() {
        Deque<CommandId> stack = BOUND.get();
        Optional<CommandId> current = Optional.ofNullable(stack.peek());
        if (stack.isEmpty()) {
            BOUND.remove();
        }
        return current;
    }
}
