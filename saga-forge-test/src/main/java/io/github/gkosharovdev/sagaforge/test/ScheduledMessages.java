package io.github.gkosharovdev.sagaforge.test;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The fixture's scheduled messages ({@code specs/017-saga-deadlines} contracts/fixture.md):
 * deadlines and scheduled commands stored with their unit of work, held back until due, cancellable
 * while waiting. They roll back with the transaction that stored or cancelled them. Same rules as
 * the in-memory transport's message store (G-009-02).
 */
final class ScheduledMessages implements FixtureTransactions.Participant {

  private enum State {
    WAITING,
    CANCELLED,
    DELIVERED
  }

  private record Entry(ScheduledMessage message, State state, SagaMetadata metadata) {}

  /** A due message with the metadata of the unit of work that scheduled it (019). */
  record Due(ScheduledMessage message, SagaMetadata metadata) {}

  private Map<String, Entry> entries = new LinkedHashMap<>();

  synchronized void add(List<ScheduledMessage> scheduled, SagaMetadata metadata) {
    scheduled.forEach(
        message -> entries.put(message.messageId(), new Entry(message, State.WAITING, metadata)));
  }

  /**
   * @return the ids among {@code messageIds} that were waiting and are now cancelled
   */
  synchronized Set<String> cancel(Collection<String> messageIds) {
    Set<String> cancelled = new LinkedHashSet<>();
    for (String id : messageIds) {
      Entry entry = entries.get(id);
      if (entry != null && entry.state() == State.WAITING) {
        entries.put(id, new Entry(entry.message(), State.CANCELLED, entry.metadata()));
        cancelled.add(id);
      }
    }
    return cancelled;
  }

  /**
   * Takes the earliest waiting message due at {@code now}, the first stored among equals, marking
   * it delivered.
   */
  synchronized Optional<Due> takeDue(Instant now) {
    Optional<Entry> due =
        entries.values().stream()
            .filter(
                entry ->
                    entry.state() == State.WAITING && !entry.message().notBefore().isAfter(now))
            .min(Comparator.comparing(entry -> entry.message().notBefore()));
    due.ifPresent(
        entry ->
            entries.put(
                entry.message().messageId(),
                new Entry(entry.message(), State.DELIVERED, entry.metadata())));
    return due.map(entry -> new Due(entry.message(), entry.metadata()));
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
