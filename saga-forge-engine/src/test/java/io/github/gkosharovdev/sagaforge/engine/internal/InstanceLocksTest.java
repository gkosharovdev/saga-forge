package io.github.gkosharovdev.sagaforge.engine.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.engine.internal.InstanceLocks.Lease;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link InstanceLocks}: one lock per key, reference-counted entries (specs/004-saga-engine tasks.md T076).
 */
class InstanceLocksTest {

    private static final long BLOCKED_MILLIS = 200;
    private static final long TIMEOUT_SECONDS = 5;

    private final InstanceLocks locks = new InstanceLocks();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Acquires {@code key} on the executor thread and releases it at once; completes when it got the lock. */
    private Future<?> acquireElsewhere(Object key) {
        return executor.submit(() -> locks.acquire(key).close());
    }

    @Test
    @DisplayName("the same key serializes two threads: the second waits until the first releases")
    void sameKeySerializes() throws Exception {
        Lease held = locks.acquire("saga-1");

        Future<?> waiting = acquireElsewhere("saga-1");

        assertThatThrownBy(() -> waiting.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        held.close();
        waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("different keys do not wait for each other")
    void differentKeysIndependent() throws Exception {
        try (Lease held = locks.acquire("saga-1")) {
            acquireElsewhere("saga-2").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(held).isNotNull();
        }
    }

    @Test
    @DisplayName("an entry exists only while its key is held or waited for")
    void entriesRemovedWhenUnused() throws Exception {
        assertThat(locks.activeKeys()).isZero();
        Lease held = locks.acquire("saga-1");
        CountDownLatch submitted = new CountDownLatch(1);
        Future<?> waiting = executor.submit(() -> {
            submitted.countDown();
            locks.acquire("saga-1").close();
        });
        submitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(locks.activeKeys()).isEqualTo(1);
        held.close();
        waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(locks.activeKeys()).isZero();
    }

    @Test
    @DisplayName("close is idempotent: a second close neither throws nor releases someone else's hold")
    void closeIsIdempotent() throws Exception {
        Lease first = locks.acquire("saga-1");
        first.close();
        Lease second = locks.acquire("saga-1");

        first.close();

        assertThatThrownBy(() -> acquireElsewhere("saga-1").get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        second.close();
    }

    @Test
    @DisplayName("the lock is re-entrant for the holding thread")
    void reentrant() {
        try (Lease outer = locks.acquire("saga-1"); Lease inner = locks.acquire("saga-1")) {
            assertThat(locks.activeKeys()).isEqualTo(1);
        }
        assertThat(locks.activeKeys()).isZero();
    }

    @Test
    @DisplayName("a null key is rejected")
    void nullKeyRejected() {
        assertThatThrownBy(() -> locks.acquire(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("value keys with equal components share one lock")
    void valueKeysShareALock() throws Exception {
        record StartKey(String sagaType, String property, String value) {
        }
        Lease held = locks.acquire(new StartKey("Saga", "orderId", "o-1"));

        assertThatThrownBy(() -> acquireElsewhere(new StartKey("Saga", "orderId", "o-1"))
                .get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        held.close();
    }
}
