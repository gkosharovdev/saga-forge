package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

/**
 * Test-only contract for fixture objects whose in-memory state a harness rolls back with a unit of work.
 *
 * <p>Not a Saga Forge type: it lives in the test fixtures and stands in for the host's local transaction.
 * A harness calls {@link #snapshot()} when a unit of work begins and {@link #restore(Object)} with that
 * snapshot when it rolls back.</p>
 */
public interface Snapshottable {

    /**
     * Captures the current state.
     *
     * @return an opaque, immutable snapshot; never {@code null}
     */
    Object snapshot();

    /**
     * Replaces the current state with a snapshot taken earlier from this object's class.
     *
     * @param snapshot a value returned by {@link #snapshot()} on an instance of the same class
     * @throws IllegalArgumentException if {@code snapshot} is {@code null} or was not taken by this class
     */
    void restore(Object snapshot);
}
