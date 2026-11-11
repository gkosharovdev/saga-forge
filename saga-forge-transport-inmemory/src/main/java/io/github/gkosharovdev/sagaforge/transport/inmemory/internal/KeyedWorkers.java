package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code workers} serial lanes over one executor: work with the same key always runs on the same lane, in submission
 * order; work without a key goes round robin. Uses a host executor if given, otherwise its own daemon threads.
 */
final class KeyedWorkers {

    private final Lane[] lanes;
    private final ExecutorService owned;
    private final AtomicInteger roundRobin = new AtomicInteger();

    KeyedWorkers(int workers, Executor hostExecutor) {
        AtomicInteger threads = new AtomicInteger();
        this.owned = hostExecutor == null ? Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "saga-forge-transport-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }) : null;
        Executor executor = hostExecutor != null ? hostExecutor : owned;
        this.lanes = new Lane[workers];
        for (int i = 0; i < workers; i++) {
            lanes[i] = new Lane(executor);
        }
    }

    void submit(String key, Runnable work) {
        int lane = key == null ? Math.floorMod(roundRobin.getAndIncrement(), lanes.length)
                : Math.floorMod(key.hashCode(), lanes.length);
        lanes[lane].submit(work);
    }

    /** Stops the owned threads after the queued work, waiting up to {@code millis}. */
    void shutdown(long millis) throws InterruptedException {
        if (owned != null) {
            owned.shutdown();
            owned.awaitTermination(millis, TimeUnit.MILLISECONDS);
        }
    }

    /** Runs submitted work one at a time, in order, on the shared executor. */
    private static final class Lane {

        private final Executor executor;
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private boolean running;

        Lane(Executor executor) {
            this.executor = executor;
        }

        void submit(Runnable work) {
            synchronized (this) {
                queue.add(work);
                if (running) {
                    return;
                }
                running = true;
            }
            executor.execute(this::drain);
        }

        private void drain() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = queue.poll();
                    if (next == null) {
                        running = false;
                        return;
                    }
                }
                next.run();
            }
        }
    }
}
