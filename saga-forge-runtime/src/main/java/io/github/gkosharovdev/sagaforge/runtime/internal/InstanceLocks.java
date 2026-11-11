package io.github.gkosharovdev.sagaforge.runtime.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-process locks keyed by value, one fair {@link ReentrantLock} per key, held through a {@link Lease}
 * (research.md §3, data-model.md "Locks", FR-025, FR-027, FR-028).
 *
 * <p>The engine uses one instance per key kind: {@code sagaId}s (the instance lock) and
 * (saga type, association property, association value) (the duplicate-start lock). Different keys never wait for
 * each other. An entry exists only while a thread holds or waits for its key: it is reference-counted and removed
 * when the last one leaves, so the map does not grow with the number of instances ever handled.</p>
 *
 * <p>Thread-safe. A lease is released on the thread that acquired it.</p>
 */
final class InstanceLocks {

    /** A held lock; {@link #close()} releases it, and closing again does nothing. */
    interface Lease extends AutoCloseable {

        @Override
        void close();
    }

    /** A key's lock and the number of threads holding or waiting for it; guarded by the map's compute. */
    private static final class Entry {

        private final ReentrantLock lock = new ReentrantLock(true);
        private int users;
    }

    private final ConcurrentMap<Object, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Blocks until the calling thread holds {@code key}'s lock. Re-entrant: a thread already holding it gets a
     * further lease at once.
     *
     * @throws IllegalArgumentException if {@code key} is {@code null}
     */
    Lease acquire(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        Entry entry = entries.compute(key, (k, existing) -> {
            Entry used = existing != null ? existing : new Entry();
            used.users++;
            return used;
        });
        entry.lock.lock();
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                release(key, entry);
            }
        };
    }

    /** The number of keys currently held or waited for. */
    int activeKeys() {
        return entries.size();
    }

    private void release(Object key, Entry entry) {
        entry.lock.unlock();
        entries.computeIfPresent(key, (k, current) -> --current.users == 0 ? null : current);
    }
}
