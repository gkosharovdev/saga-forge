package io.github.gkosharovdev.sagaforge.core.execution.harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Chronological log of what the saga test harness did with every event and unit of work.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <p>Each {@link Entry} has a {@link Kind}, the event it concerns, and, where known, the saga type, saga id
 * and step name. Entries are appended in the order things happen. Not thread-safe.</p>
 */
public final class HarnessEventLog {

    /** What happened to an event, or to a unit of work. */
    public enum Kind {
        /** A delivered (transport) event triggered a step and opened a unit of work for the instance. */
        DELIVERED,
        /** An event recorded inside a unit of work was delivered in-process to a step of the same instance. */
        IN_PROCESS,
        /** An event recorded inside a unit of work was handed to {@code EventDispatcher.dispatch} after commit. */
        DISPATCHED,
        /** An event recorded inside a unit of work, not delivered in-process, was dropped by a rollback. */
        DISCARDED,
        /** A delivered event triggers a non-start step but correlates to no active instance (FR-003). */
        UNMATCHED,
        /** A delivered event triggers a non-start step of an instance that is already terminal (FR-003). */
        UNEXPECTED,
        /** A delivered event triggers no step of any registered saga. */
        IGNORED,
        /** A unit of work committed; {@code event} is the event that opened it. */
        COMMITTED,
        /** A unit of work rolled back; {@code event} is the event that opened it. */
        ROLLED_BACK
    }

    /**
     * One log entry.
     *
     * @param kind     what happened; never {@code null}
     * @param event    the event concerned (for {@code COMMITTED}/{@code ROLLED_BACK}, the event that opened the
     *                 unit of work); never {@code null}
     * @param sagaType the saga type concerned, or {@code null} (for {@code IGNORED})
     * @param sagaId   the saga instance concerned, or {@code null} if none (for example {@code UNMATCHED})
     * @param stepName the step concerned, or {@code null} if none (for {@code DISPATCHED}, {@code DISCARDED},
     *                 {@code IGNORED})
     */
    public record Entry(Kind kind, Object event, Class<?> sagaType, String sagaId, String stepName) {
    }

    private final List<Entry> entries = new ArrayList<>();

    void add(Kind kind, Object event, Class<?> sagaType, String sagaId, String stepName) {
        entries.add(new Entry(kind, event, sagaType, sagaId, stepName));
    }

    /** Every entry, in the order it was logged. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Entries of {@code kind}, in the order they were logged. */
    public List<Entry> entries(Kind kind) {
        return entries.stream().filter(entry -> entry.kind() == kind).toList();
    }

    /** Events of entries of {@code kind}, in the order they were logged. */
    public List<Object> events(Kind kind) {
        return entries(kind).stream().map(Entry::event).toList();
    }

    /** Shorthand for {@code events(Kind.UNMATCHED)}. */
    public List<Object> unmatched() {
        return events(Kind.UNMATCHED);
    }

    /** Shorthand for {@code events(Kind.UNEXPECTED)}. */
    public List<Object> unexpected() {
        return events(Kind.UNEXPECTED);
    }

    /** Clears the log. */
    public void clear() {
        entries.clear();
    }
}
