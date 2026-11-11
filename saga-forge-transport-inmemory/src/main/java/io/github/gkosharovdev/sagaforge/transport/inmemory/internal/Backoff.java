package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import java.time.Duration;

/** Wait before the next delivery attempt: {@code initial} doubled per earlier failure, at most {@code max}. */
final class Backoff {

    private Backoff() {
    }

    /**
     * @param attempts the attempts made so far, including the one that just failed; at least 1
     * @return {@code min(max, initial * 2^(attempts - 1))}
     */
    static Duration after(int attempts, Duration initial, Duration max) {
        Duration wait = initial;
        for (int i = 1; i < attempts && wait.compareTo(max) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(max) > 0 ? max : wait;
    }
}
