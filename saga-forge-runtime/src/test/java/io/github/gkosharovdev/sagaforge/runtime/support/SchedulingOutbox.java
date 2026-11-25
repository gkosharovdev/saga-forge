package io.github.gkosharovdev.sagaforge.runtime.support;

import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An {@code IN_TRANSACTION} {@link Outbox} test double that supports scheduling (specs/017-saga-deadlines): it keeps
 * the committed batches like {@link CapturingOutbox} and the scheduled messages they carry, applies {@link #cancel}
 * when the transaction commits, and hands a test the messages due at an instant ({@link #due(Instant)}) so the test can
 * deliver them, as a relay would.
 *
 * <p>A message {@linkplain #lease(String) leased} by a test cannot be cancelled, like a message the relay is
 * delivering. Thread-safe enough for single-threaded tests; methods are synchronized.</p>
 */
public final class SchedulingOutbox implements Outbox {

    /** One scheduled message and whether it was cancelled, delivered or is being delivered. */
    public enum State { WAITING, LEASED, CANCELLED, DELIVERED }

    private final InMemoryTransactionBoundary boundary;
    private final List<OutgoingMessages> committed = new ArrayList<>();
    private final Map<String, ScheduledMessage> scheduled = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final List<List<String>> cancelCalls = new ArrayList<>();
    private boolean commands;

    public SchedulingOutbox(InMemoryTransactionBoundary boundary) {
        this.boundary = boundary;
    }

    /** Also accepts outbox commands. */
    public SchedulingOutbox supportingCommands() {
        commands = true;
        return this;
    }

    @Override
    public Timing timing() {
        return Timing.IN_TRANSACTION;
    }

    @Override
    public boolean supportsCommands() {
        return commands;
    }

    @Override
    public boolean supportsScheduling() {
        return true;
    }

    @Override
    public synchronized void store(OutgoingMessages batch) {
        scope().onCommit(() -> keep(batch));
    }

    @Override
    public synchronized Set<String> cancel(Collection<String> messageIds) {
        Scope scope = scope();
        List<String> ids = List.copyOf(messageIds);
        Set<String> cancellable = new LinkedHashSet<>();
        ids.forEach(id -> {
            if (states.get(id) == State.WAITING) {
                cancellable.add(id);
            }
        });
        scope.onCommit(() -> apply(ids, cancellable));
        return cancellable;
    }

    /** The committed batches, in commit order. */
    public synchronized List<OutgoingMessages> committed() {
        return List.copyOf(committed);
    }

    /** Every scheduled message ever committed, in commit order. */
    public synchronized List<ScheduledMessage> scheduled() {
        return List.copyOf(scheduled.values());
    }

    /** The state of the scheduled message {@code id}, or {@code null} if none was committed. */
    public synchronized State state(String id) {
        return states.get(id);
    }

    /** The id lists of every committed {@link #cancel} call. */
    public synchronized List<List<String>> cancelCalls() {
        return List.copyOf(cancelCalls);
    }

    /** The waiting messages due at {@code now}, earliest first; marks none. */
    public synchronized List<ScheduledMessage> due(Instant now) {
        return scheduled.values().stream()
                .filter(message -> states.get(message.messageId()) == State.WAITING)
                .filter(message -> !message.notBefore().isAfter(now))
                .sorted((left, right) -> left.notBefore().compareTo(right.notBefore()))
                .toList();
    }

    /** Takes the message for delivery: it can no longer be cancelled. */
    public synchronized ScheduledMessage lease(String id) {
        states.put(id, State.LEASED);
        return scheduled.get(id);
    }

    /** Marks a leased message delivered. */
    public synchronized void delivered(String id) {
        states.put(id, State.DELIVERED);
    }

    private synchronized void keep(OutgoingMessages batch) {
        committed.add(batch);
        batch.scheduled().forEach(message -> {
            scheduled.put(message.messageId(), message);
            states.put(message.messageId(), State.WAITING);
        });
    }

    private synchronized void apply(List<String> ids, Set<String> cancellable) {
        cancelCalls.add(ids);
        cancellable.forEach(id -> {
            if (states.get(id) == State.WAITING) {
                states.put(id, State.CANCELLED);
            }
        });
    }

    private Scope scope() {
        return boundary.current()
                .flatMap(transaction -> transaction.resource(Scope.class))
                .orElseThrow(() -> new IllegalStateException(
                        "SchedulingOutbox needs an active InMemoryTransactionBoundary transaction"));
    }
}
