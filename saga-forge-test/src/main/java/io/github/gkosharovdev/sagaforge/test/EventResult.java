package io.github.gkosharovdev.sagaforge.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;

/**
 * What one published event caused ({@code specs/009-saga-test-fixture} data-model.md): the runtime's reports for
 * it and for everything the fixture's transport delivered as a consequence, the commands handled, and the instances
 * before and after. Expectations read it; {@link Then#result()} hands it to custom assertions.
 */
public final class EventResult {

    private final Object event;
    private final List<HandlingReport> reports;
    private final List<RecordedCommand> commands;
    private final List<RecordedCommand> allAttempts;
    private final Map<String, InstanceView> before;
    private final List<InstanceView> touched;
    private final List<RuntimeException> outboxFailures;
    private final List<FixtureLogger.Logged> logged;

    EventResult(Object event, List<HandlingReport> reports, List<RecordedCommand> commands,
            List<RecordedCommand> allAttempts, Map<String, InstanceView> before, List<InstanceView> touched,
            List<RuntimeException> outboxFailures, List<FixtureLogger.Logged> logged) {
        this.event = event;
        this.reports = List.copyOf(reports);
        this.commands = List.copyOf(commands);
        this.allAttempts = List.copyOf(allAttempts);
        this.before = Map.copyOf(before);
        this.touched = List.copyOf(touched);
        this.outboxFailures = List.copyOf(outboxFailures);
        this.logged = List.copyOf(logged);
    }

    /** The published event. */
    public Object event() {
        return event;
    }

    /** The runtime's report for the event, then those of every event the transport delivered as a consequence. */
    public List<HandlingReport> reports() {
        return reports;
    }

    /** The results of the published event itself: one per saga type and instance it reached. */
    public List<SagaHandlingResult> results() {
        return reports.get(0).results();
    }

    /** {@code true} if no saga instance handled the event: no saga type reacts to it, or none correlates (FR-007). */
    public boolean unhandled() {
        return results().stream().allMatch(result -> result.outcome() == HandlingOutcome.UNMATCHED
                || result.outcome() == HandlingOutcome.UNEXPECTED || result.outcome() == HandlingOutcome.NOT_HANDLED);
    }

    /** The commands handled in committed units of work, in order; earlier history does not count. */
    public List<RecordedCommand> commands() {
        return commands;
    }

    /** Every handling of a command, including attempts that rolled back and were retried. */
    public List<RecordedCommand> allAttempts() {
        return allAttempts;
    }

    /** Every instance the event or its consequences created or changed, as stored afterwards. */
    public List<InstanceView> instances() {
        return touched;
    }

    /** {@code sagaId}'s instance as it was before the event, if it existed. */
    public Optional<InstanceView> before(String sagaId) {
        return Optional.ofNullable(before.get(sagaId));
    }

    /** Exceptions of outbox commands whose delivery failed; a transport would redeliver them, the fixture does not. */
    public List<RuntimeException> outboxFailures() {
        return outboxFailures;
    }

    /** An exception of type {@code causeType} the runtime logged for {@code sagaId} during the event. */
    Optional<Throwable> cause(String sagaId, String causeType) {
        return logged.stream()
                .filter(entry -> sagaId.equals(entry.sagaId()))
                .map(FixtureLogger.Logged::thrown)
                .map(thrown -> find(thrown, causeType))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<Throwable> find(Throwable thrown, String causeType) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current.getClass().getName().equals(causeType)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }
}
