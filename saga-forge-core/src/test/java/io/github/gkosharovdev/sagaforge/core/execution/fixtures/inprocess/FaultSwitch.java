package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

/**
 * One-shot failure toggle shared by the handler fixtures. Armed by {@link #arm(RuntimeException)}, it throws
 * once from {@link #throwIfArmed()} and disarms itself.
 */
final class FaultSwitch {

    private RuntimeException pending;

    void arm(RuntimeException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        pending = failure;
    }

    void disarm() {
        pending = null;
    }

    void throwIfArmed() {
        RuntimeException failure = pending;
        if (failure != null) {
            pending = null;
            throw failure;
        }
    }
}
