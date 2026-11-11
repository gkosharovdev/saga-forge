package io.github.gkosharovdev.sagaforge.runtime;

import java.time.Duration;

/**
 * How the engine waits before running a step's unit of work again ({@code specs/005-step-retry} FR-014). The
 * default, {@link #sleeping()}, blocks the calling thread; tests pass a recording implementation through
 * {@link SagaRuntime.Builder#retryPause(RetryPause)}.
 *
 * <p>An implementation throws {@link InterruptedException} when the waiting thread is interrupted. The engine then
 * stops waiting, restores the interrupt flag and reports the event {@code NOT_HANDLED}, keeping the claimed attempt
 * recorded so that a redelivery continues from it.</p>
 */
@FunctionalInterface
public interface RetryPause {

    /**
     * Waits for {@code wait}.
     *
     * @param wait how long to wait; not negative
     * @throws InterruptedException if the waiting thread is interrupted
     */
    void pause(Duration wait) throws InterruptedException;

    /**
     * @return a pause that sleeps the calling thread
     */
    static RetryPause sleeping() {
        return wait -> Thread.sleep(wait.toMillis());
    }
}
