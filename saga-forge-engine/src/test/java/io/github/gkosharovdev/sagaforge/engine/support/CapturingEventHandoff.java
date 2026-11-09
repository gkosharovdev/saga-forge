package io.github.gkosharovdev.sagaforge.engine.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.github.gkosharovdev.sagaforge.core.spi.EventHandoff;
import io.github.gkosharovdev.sagaforge.core.spi.HandedOffEvents;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary.Scope;

/**
 * {@link EventHandoff} that records every batch it receives, for engine tests.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main} (Constitution Principle IV).</p>
 *
 * <ul>
 *   <li>{@link #received()} lists every batch handed to it, including one it then failed on.</li>
 *   <li>{@link #committed()} lists the batches that survived: with {@link Timing#AFTER_COMMIT} every batch it handled
 *       without failing; with {@link Timing#IN_TRANSACTION} only those whose receiving transaction committed, joined
 *       through {@link InMemoryTransactionBoundary.Scope}, as an outbox's rows would.</li>
 *   <li>{@link #failNext(RuntimeException)} makes the next {@code handOff} throw after recording the batch.</li>
 *   <li>An optional delegate receives each batch it handled without failing, for example a
 *       {@code DispatchingEventHandoff} over a looping-back transport.</li>
 * </ul>
 *
 * <p>Thread-safe.</p>
 */
public final class CapturingEventHandoff implements EventHandoff {

    private static final EventHandoff NO_DELEGATE = batch -> { };

    private final Timing timing;
    private final InMemoryTransactionBoundary boundary;
    private final EventHandoff delegate;
    private final List<HandedOffEvents> received = new CopyOnWriteArrayList<>();
    private final List<HandedOffEvents> committed = new CopyOnWriteArrayList<>();
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

    private CapturingEventHandoff(Timing timing, InMemoryTransactionBoundary boundary, EventHandoff delegate) {
        this.timing = timing;
        this.boundary = boundary;
        this.delegate = delegate;
    }

    /** @return an {@link Timing#AFTER_COMMIT} handoff with no delegate */
    public static CapturingEventHandoff afterCommit() {
        return new CapturingEventHandoff(Timing.AFTER_COMMIT, null, NO_DELEGATE);
    }

    /**
     * @param delegate receives each batch after it is recorded; must not be {@code null}
     * @return an {@link Timing#AFTER_COMMIT} handoff that passes batches on to {@code delegate}
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public static CapturingEventHandoff afterCommit(EventHandoff delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        return new CapturingEventHandoff(Timing.AFTER_COMMIT, null, delegate);
    }

    /**
     * @param boundary the boundary whose active transaction receives the batches; must not be {@code null}
     * @return an {@link Timing#IN_TRANSACTION} handoff that keeps a batch only if its transaction commits
     * @throws IllegalArgumentException if {@code boundary} is {@code null}
     */
    public static CapturingEventHandoff inTransaction(InMemoryTransactionBoundary boundary) {
        if (boundary == null) {
            throw new IllegalArgumentException("boundary must not be null");
        }
        return new CapturingEventHandoff(Timing.IN_TRANSACTION, boundary, NO_DELEGATE);
    }

    @Override
    public Timing timing() {
        return timing;
    }

    /**
     * @throws IllegalArgumentException if {@code batch} is {@code null}
     * @throws IllegalStateException    with {@link Timing#IN_TRANSACTION}, if no transaction is active
     * @throws RuntimeException         the failure set with {@link #failNext(RuntimeException)}
     */
    @Override
    public void handOff(HandedOffEvents batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        received.add(batch);
        RuntimeException injected = nextFailure.getAndSet(null);
        if (injected != null) {
            throw injected;
        }
        keep(batch);
        delegate.handOff(batch);
    }

    /** @return every batch received, in order; unmodifiable */
    public List<HandedOffEvents> received() {
        return List.copyOf(received);
    }

    /** @return the batches that survived (see the class description), in order; unmodifiable */
    public List<HandedOffEvents> committed() {
        return List.copyOf(committed);
    }

    /**
     * Makes the next {@code handOff} throw {@code failure} after recording the batch in {@link #received()}.
     *
     * @param failure the exception to throw; must not be {@code null}
     * @throws IllegalArgumentException if {@code failure} is {@code null}
     */
    public void failNext(RuntimeException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        nextFailure.set(failure);
    }

    private void keep(HandedOffEvents batch) {
        if (timing == Timing.AFTER_COMMIT) {
            committed.add(batch);
            return;
        }
        Scope scope = boundary.current()
                .flatMap(transaction -> transaction.resource(Scope.class))
                .orElseThrow(() -> new IllegalStateException(
                        "an IN_TRANSACTION handoff needs an active InMemoryTransactionBoundary transaction"));
        scope.onCommit(() -> committed.add(batch));
    }
}
