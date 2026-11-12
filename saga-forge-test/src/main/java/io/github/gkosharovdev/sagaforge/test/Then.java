package io.github.gkosharovdev.sagaforge.test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;

/**
 * Expectations over the event under test ({@code specs/009-saga-test-fixture} contracts/fixture-api.md). Every method
 * returns this object, so calls chain; each throws {@link SagaExpectationError} on a mismatch, with a message naming
 * the instance, the event, the steps that ran and the expected and actual values (FR-014).
 *
 * <p>Expectations about an instance address the one instance the event touched, or the one chosen with
 * {@link #instance(Class, String)}. Command expectations cover the commands handled in committed units of work caused
 * by the event, in order; with a chosen instance, only that instance's commands.</p>
 */
public final class Then {

    private final SagaFixture fixture;
    private final EventResult result;
    private final InstanceView chosen;

    Then(SagaFixture fixture, EventResult result, InstanceView chosen) {
        this.fixture = fixture;
        this.result = result;
        this.chosen = chosen;
    }

    /** Switches the subject to the instance of {@code sagaType} associated with {@code associationValue}. */
    public Then instance(Class<?> sagaType, String associationValue) {
        return new Then(fixture, result, fixture.instance(sagaType, associationValue));
    }

    /** The result of the event, for custom assertions. */
    public EventResult result() {
        return result;
    }

    // --- commands --------------------------------------------------------------------------------------------------

    /** Exactly these commands were dispatched, in this order, compared with {@code equals}. */
    public Then expectCommands(Object... expected) {
        List<Object> actual = commands();
        List<Object> wanted = Arrays.asList(expected);
        if (!actual.equals(wanted)) {
            throw fail(Messages.expectedActual("expected commands", wanted, "dispatched", actual)
                    + Messages.commandCount(wanted.size(), actual.size()));
        }
        return this;
    }

    /** One command per predicate, in order. */
    @SafeVarargs
    public final Then expectCommandsMatching(Predicate<Object>... predicates) {
        List<Object> actual = commands();
        boolean matches = actual.size() == predicates.length;
        for (int i = 0; matches && i < predicates.length; i++) {
            matches = predicates[i].test(actual.get(i));
        }
        if (!matches) {
            throw fail(Messages.expectedActual("expected", predicates.length + " commands matching the predicates,"
                    + " in order", "dispatched", actual));
        }
        return this;
    }

    /** No command was dispatched. */
    public Then expectNoCommands() {
        List<Object> actual = commands();
        if (!actual.isEmpty()) {
            throw fail(Messages.expectedActual("expected commands", "[]", "dispatched", actual));
        }
        return this;
    }

    /** At least one dispatched command is a {@code type} matching {@code predicate}. */
    public <T> Then expectCommand(Class<T> type, Predicate<? super T> predicate) {
        List<Object> actual = commands();
        boolean found = actual.stream().filter(type::isInstance).map(type::cast).anyMatch(predicate);
        if (!found) {
            throw fail(Messages.expectedActual("expected a command", type.getSimpleName() + " matching the predicate",
                    "dispatched", actual));
        }
        return this;
    }

    // --- handling --------------------------------------------------------------------------------------------------

    /** Some saga instance handled the event (FR-007). */
    public Then expectHandled() {
        if (result.unhandled()) {
            throw fail("  expected the event to be handled, but no saga instance handled it\n");
        }
        return this;
    }

    /** No saga instance handled the event: no saga type reacts to it, or none correlates. */
    public Then expectUnhandled() {
        if (!result.unhandled()) {
            throw fail("  expected the event to be unhandled, but it was handled\n");
        }
        return this;
    }

    /** The subject's handling outcome ({@code WAITING}, {@code COMPLETED}, {@code DUPLICATE}, ...). */
    public Then expectOutcome(HandlingOutcome outcome) {
        HandlingOutcome actual = handling().outcome();
        if (actual != outcome) {
            throw fail(Messages.expectedActual("expected outcome", outcome, "actual", actual));
        }
        return this;
    }

    /** The event started the subject instance. */
    public Then expectStarted() {
        if (!handling().started()) {
            throw fail("  expected the event to start the instance, but it did not\n");
        }
        return this;
    }

    // --- steps and compensations -----------------------------------------------------------------------------------

    /** {@code step} completed in response to the event. */
    public Then expectCompletedStep(String step) {
        List<String> completed = Messages.stepsCompleted(result, subject());
        if (!completed.contains(step)) {
            throw fail(Messages.expectedActual("expected completed step", step, "completed", completed));
        }
        return this;
    }

    /** These compensations ran in response to the event, in this order (compensation method names). */
    public Then expectCompensations(String... compensations) {
        List<String> actual = Messages.compensationsRun(fixture, result, subject());
        List<String> wanted = Arrays.asList(compensations);
        if (!actual.equals(wanted)) {
            throw fail(Messages.expectedActual("expected compensations", wanted, "ran", actual));
        }
        return this;
    }

    /**
     * How many times {@code step}'s unit of work ran: the committed step's attempts, or those of the recorded failure
     * when the step failed.
     */
    public Then expectAttempts(String step, int attempts) {
        InstanceView instance = subject();
        StepRecord record = instance.steps().get(step);
        int actual = record == null ? 0 : record.attempts();
        if (actual == 0) {
            actual = instance.failure().filter(failure -> step.equals(failure.stepName()))
                    .map(SagaFailure::attempts).orElse(0);
        }
        if (actual != attempts) {
            throw fail(Messages.expectedActual("expected attempts of " + step, attempts, "actual", actual));
        }
        return this;
    }

    // --- status, outcome, failure ----------------------------------------------------------------------------------

    /** The subject's status afterwards. */
    public Then expectStatus(SagaStatus status) {
        SagaStatus actual = subject().status();
        if (actual != status) {
            throw fail(Messages.expectedActual("expected status", status, "actual", actual));
        }
        return this;
    }

    /** The subject is {@code COMPLETED} with {@code outcomeStep} as its {@code @EndSaga} outcome. */
    public Then expectEndedWith(String outcomeStep) {
        InstanceView instance = subject();
        if (instance.status() != SagaStatus.COMPLETED || !outcomeStep.equals(instance.outcomeStep().orElse(null))) {
            throw fail(Messages.expectedActual("expected", "COMPLETED with outcome " + outcomeStep, "actual",
                    instance.status() + instance.outcomeStep().map(step -> " with outcome " + step).orElse("")));
        }
        return this;
    }

    /** The subject is {@code COMPENSATED}, with {@code triggerStep} recorded as the cause. */
    public Then expectCompensatedAfter(String triggerStep) {
        InstanceView instance = subject();
        String trigger = instance.compensationTrigger().map(CompensationTrigger::stepName).orElse(null);
        if (instance.status() != SagaStatus.COMPENSATED || !triggerStep.equals(trigger)) {
            throw fail(Messages.expectedActual("expected", "COMPENSATED after " + triggerStep, "actual",
                    instance.status() + (trigger == null ? "" : " after " + trigger)));
        }
        return this;
    }

    /**
     * The recorded failure has this type and classification: the instance's own, a failed compensation's, or the
     * one that triggered compensation.
     */
    public Then expectFailure(Class<? extends Throwable> type, FailureClassification classification) {
        Optional<SagaFailure> failure = subject().failure();
        boolean matches = failure.isPresent() && failure.get().causeType().equals(type.getName())
                && failure.get().classification() == classification;
        if (!matches) {
            throw fail(Messages.expectedActual("expected failure", type.getName() + ", " + classification,
                    "actual", failure.map(Messages::describe).orElse("none")));
        }
        return this;
    }

    // --- SagaContext -----------------------------------------------------------------------------------------------

    /** The subject's {@code SagaContext} holds {@code value} under {@code key} ({@code equals}). */
    public Then expectContext(String key, Object value) {
        Map<String, Object> context = subject().context();
        Object actual = context.get(key);
        if (!context.containsKey(key) || !Objects.equals(actual, value)) {
            throw fail(Messages.expectedActual("expected SagaContext '" + key + "'", typed(value), "actual",
                    context.containsKey(key) ? typed(actual) : "absent"));
        }
        return this;
    }

    /** The subject's {@code SagaContext} holds nothing under {@code key}. */
    public Then expectNoContext(String key) {
        Map<String, Object> context = subject().context();
        if (context.containsKey(key)) {
            throw fail(Messages.expectedActual("expected SagaContext '" + key + "'", "absent", "actual",
                    typed(context.get(key))));
        }
        return this;
    }

    // --- internals -------------------------------------------------------------------------------------------------

    private List<Object> commands() {
        return result.commands().stream()
                .filter(command -> chosen == null || chosen.sagaId().equals(command.commandId().sagaId()))
                .map(RecordedCommand::command)
                .toList();
    }

    private Optional<InstanceView> trySubject() {
        if (chosen != null) {
            return fixture.current(chosen.sagaId());
        }
        return result.instances().size() == 1 ? Optional.of(result.instances().get(0)) : Optional.empty();
    }

    private InstanceView subject() {
        return trySubject().orElseThrow(() -> fail(result.instances().isEmpty()
                ? "  the event reached no saga instance\n"
                : "  the event touched " + result.instances().size() + " instances: "
                        + result.instances().stream().map(InstanceView::describe).toList()
                        + "; choose one with instance(sagaType, associationValue)\n"));
    }

    private SagaHandlingResult handling() {
        Optional<InstanceView> subject = trySubject();
        if (subject.isPresent()) {
            String sagaId = subject.get().sagaId();
            return result.reports().stream().flatMap(report -> report.results().stream())
                    .filter(handled -> sagaId.equals(handled.sagaId()))
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> fail("  the event did not reach " + subject.get().describe() + "\n"));
        }
        if (result.results().size() == 1) {
            return result.results().get(0);
        }
        throw fail(result.results().isEmpty() ? "  no saga type reacts to the event\n"
                : "  the event has " + result.results().size() + " results; choose an instance with"
                        + " instance(sagaType, associationValue)\n");
    }

    private SagaExpectationError fail(String detail) {
        Optional<InstanceView> subject = trySubject();
        String message = Messages.situation(fixture, result, subject) + detail;
        Optional<Throwable> cause = subject.flatMap(instance -> instance.failure()
                .flatMap(failure -> result.cause(instance.sagaId(), failure.causeType())));
        return cause.map(thrown -> new SagaExpectationError(message.stripTrailing(), thrown))
                .orElseGet(() -> new SagaExpectationError(message.stripTrailing()));
    }

    private static String typed(Object value) {
        return value == null ? "null" : value + " (" + value.getClass().getSimpleName() + ")";
    }
}
