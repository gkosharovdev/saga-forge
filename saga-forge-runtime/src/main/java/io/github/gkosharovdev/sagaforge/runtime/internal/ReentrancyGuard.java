package io.github.gkosharovdev.sagaforge.runtime.internal;

/**
 * The thread-bound "engine unit of work running" marker (FR-007, research.md §6, contracts/event-receiver.md
 * "Reentrancy and threading").
 *
 * <p>A unit of work, saga-scoped or not, {@linkplain #enter() enters} right after its transaction began and
 * {@linkplain #exit() exits} once its commit or rollback has finished, before any {@code AFTER_COMMIT} outbox.
 * {@code EventReceiver.receive} calls {@link #requireNotRunning()} first: a call from a step, a handler or an
 * {@code IN_TRANSACTION} outbox is rejected, while a call from inside an {@code AFTER_COMMIT} outbox (marker cleared,
 * instance lock released) runs as a new unit of work.</p>
 *
 * <p>The marker is per thread and shared by every engine in the JVM: a unit of work of one engine also blocks
 * {@code receive} on another engine from the same thread, which would otherwise begin a second transaction on it.
 * Entries nest as a count, so an unbalanced {@link #exit()} is a programming error.</p>
 *
 * <p>Thread-safe: each thread sees only its own marker.</p>
 */
final class ReentrancyGuard {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private ReentrancyGuard() {
    }

    /** Marks the calling thread as running an engine unit of work. */
    static void enter() {
        DEPTH.get()[0]++;
    }

    /**
     * Clears one {@link #enter()} of the calling thread.
     *
     * @throws IllegalStateException if the thread is not marked
     */
    static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] == 0) {
            throw new IllegalStateException("ReentrancyGuard.exit() without a matching enter()");
        }
        if (--depth[0] == 0) {
            DEPTH.remove();
        }
    }

    /** {@code true} while an engine unit of work runs on the calling thread. */
    static boolean isRunning() {
        return DEPTH.get()[0] > 0;
    }

    /**
     * Rejects a call made while an engine unit of work runs on the calling thread. The rejection is first reported
     * to the bound unit of work, if any, so it fails even if the step or handler catches the exception.
     *
     * @throws IllegalStateException if a unit of work is running on this thread
     */
    static void requireNotRunning() {
        if (!isRunning()) {
            DEPTH.remove();
            return;
        }
        IllegalStateException rejection = new IllegalStateException("EventReceiver.receive was called while a"
                + " saga engine unit of work is running on thread " + Thread.currentThread().getName()
                + "; deliver the event after the unit of work committed, for example from an AFTER_COMMIT outbox");
        UnitOfWork.bound().ifPresent(unitOfWork -> unitOfWork.failed(rejection));
        throw rejection;
    }
}
