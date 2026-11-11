package io.github.gkosharovdev.sagaforge.runtime.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary.Scope;

/**
 * {@link Outbox} that records every batch it receives, for engine tests.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main} (Constitution Principle IV).</p>
 *
 * <ul>
 *   <li>{@link #received()} lists every batch handed to it, including one it then failed on.</li>
 *   <li>{@link #committed()} lists the batches that survived: with {@link Timing#AFTER_COMMIT} every batch it handled
 *       without failing; with {@link Timing#IN_TRANSACTION} only those whose receiving transaction committed, joined
 *       through {@link InMemoryTransactionBoundary.Scope}, as an outbox's rows would.</li>
 *   <li>{@link #failNext(RuntimeException)} makes the next {@code store} throw after recording the batch.</li>
 *   <li>An optional delegate receives each batch it handled without failing, for example a
 *       {@code ImmediateDispatchOutbox} over a looping-back transport.</li>
 *   <li>{@link #notified()} lists the batches the engine passed to {@link #committed(OutgoingMessages)} after
 *       commit (007), and {@link #failNextNotification(RuntimeException)} makes the next such call throw.</li>
 *   <li>{@link #supportingCommands()} makes {@link #supportsCommands()} report {@code true} (007).</li>
 * </ul>
 *
 * <p>Thread-safe.</p>
 */
public final class CapturingOutbox implements Outbox {

    private static final Outbox NO_DELEGATE = batch -> { };

    private final Timing timing;
    private final InMemoryTransactionBoundary boundary;
    private final Outbox delegate;
    private final List<OutgoingMessages> received = new CopyOnWriteArrayList<>();
    private final List<OutgoingMessages> committed = new CopyOnWriteArrayList<>();
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();
    private final List<OutgoingMessages> notified = new CopyOnWriteArrayList<>();
    private final AtomicReference<RuntimeException> nextNotificationFailure = new AtomicReference<>();
    private volatile boolean commands;
    private volatile boolean observing;

    private CapturingOutbox(Timing timing, InMemoryTransactionBoundary boundary, Outbox delegate) {
        this.timing = timing;
        this.boundary = boundary;
        this.delegate = delegate;
    }

    /** @return an {@link Timing#AFTER_COMMIT} outbox with no delegate */
    public static CapturingOutbox afterCommit() {
        return new CapturingOutbox(Timing.AFTER_COMMIT, null, NO_DELEGATE);
    }

    /**
     * @param delegate receives each batch after it is recorded; must not be {@code null}
     * @return an {@link Timing#AFTER_COMMIT} outbox that passes batches on to {@code delegate}
     * @throws IllegalArgumentException if {@code delegate} is {@code null}
     */
    public static CapturingOutbox afterCommit(Outbox delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        return new CapturingOutbox(Timing.AFTER_COMMIT, null, delegate);
    }

    /**
     * @param boundary the boundary whose active transaction receives the batches; must not be {@code null}
     * @return an {@link Timing#IN_TRANSACTION} outbox that keeps a batch only if its transaction commits
     * @throws IllegalArgumentException if {@code boundary} is {@code null}
     */
    public static CapturingOutbox inTransaction(InMemoryTransactionBoundary boundary) {
        if (boundary == null) {
            throw new IllegalArgumentException("boundary must not be null");
        }
        return new CapturingOutbox(Timing.IN_TRANSACTION, boundary, NO_DELEGATE);
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
    public void store(OutgoingMessages batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        received.add(batch);
        RuntimeException injected = nextFailure.getAndSet(null);
        if (injected != null) {
            throw injected;
        }
        keep(batch);
        delegate.store(batch);
    }

    @Override
    public boolean supportsCommands() {
        return commands;
    }

    /** Records the notification, then throws the failure set with {@link #failNextNotification}, if any. */
    @Override
    public void committed(OutgoingMessages batch) {
        notified.add(batch);
        RuntimeException injected = nextNotificationFailure.getAndSet(null);
        if (injected != null) {
            throw injected;
        }
    }

    @Override
    public boolean observesRecordedEvents() {
        return observing;
    }

    /** @return this outbox, now reporting {@link #observesRecordedEvents()} as {@code true} (008) */
    public CapturingOutbox observingRecordedEvents() {
        observing = true;
        return this;
    }

    /** @return this outbox, now reporting {@link #supportsCommands()} as {@code true} */
    public CapturingOutbox supportingCommands() {
        commands = true;
        return this;
    }

    /** @return every batch passed to {@link #committed(OutgoingMessages)}, in order; unmodifiable */
    public List<OutgoingMessages> notified() {
        return List.copyOf(notified);
    }

    /**
     * Makes the next {@link #committed(OutgoingMessages)} throw {@code failure} after recording the batch.
     *
     * @param failure the exception to throw; must not be {@code null}
     */
    public void failNextNotification(RuntimeException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        nextNotificationFailure.set(failure);
    }

    /** @return every batch received, in order; unmodifiable */
    public List<OutgoingMessages> received() {
        return List.copyOf(received);
    }

    /** @return the batches that survived (see the class description), in order; unmodifiable */
    public List<OutgoingMessages> committed() {
        return List.copyOf(committed);
    }

    /**
     * Makes the next {@code store} throw {@code failure} after recording the batch in {@link #received()}.
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

    private void keep(OutgoingMessages batch) {
        if (timing == Timing.AFTER_COMMIT) {
            committed.add(batch);
            return;
        }
        Scope scope = boundary.current()
                .flatMap(transaction -> transaction.resource(Scope.class))
                .orElseThrow(() -> new IllegalStateException(
                        "an IN_TRANSACTION outbox needs an active InMemoryTransactionBoundary transaction"));
        scope.onCommit(() -> committed.add(batch));
    }
}
