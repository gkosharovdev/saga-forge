package io.github.gkosharovdev.sagaforge.test;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;

/**
 * The fixture's scheduled messages ({@code specs/017-saga-deadlines} contracts/fixture.md): deadlines and scheduled
 * commands stored with their unit of work, held back until due, cancellable while waiting. They roll back with the
 * transaction that stored or cancelled them. Same rules as the in-memory transport's message store (G-009-02).
 */
final class ScheduledMessages implements FixtureTransactions.Participant {

    private enum State { WAITING, CANCELLED, DELIVERED }

    private record Entry(ScheduledMessage message, State state) {
    }

    private Map<String, Entry> entries = new LinkedHashMap<>();

    synchronized void add(List<ScheduledMessage> scheduled) {
        scheduled.forEach(message -> entries.put(message.messageId(), new Entry(message, State.WAITING)));
    }

    /** @return the ids among {@code messageIds} that were waiting and are now cancelled */
    synchronized Set<String> cancel(Collection<String> messageIds) {
        Set<String> cancelled = new LinkedHashSet<>();
        for (String id : messageIds) {
            Entry entry = entries.get(id);
            if (entry != null && entry.state() == State.WAITING) {
                entries.put(id, new Entry(entry.message(), State.CANCELLED));
                cancelled.add(id);
            }
        }
        return cancelled;
    }

    /** Takes the earliest waiting message due at {@code now}, the first stored among equals, marking it delivered. */
    synchronized Optional<ScheduledMessage> takeDue(Instant now) {
        Optional<ScheduledMessage> due = entries.values().stream()
                .filter(entry -> entry.state() == State.WAITING && !entry.message().notBefore().isAfter(now))
                .map(Entry::message)
                .min(Comparator.comparing(ScheduledMessage::notBefore));
        due.ifPresent(message -> entries.put(message.messageId(), new Entry(message, State.DELIVERED)));
        return due;
    }

    @Override
    public synchronized Object snapshot() {
        return new LinkedHashMap<>(entries);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void restore(Object snapshot) {
        entries = new LinkedHashMap<>((Map<String, Entry>) snapshot);
    }
}
