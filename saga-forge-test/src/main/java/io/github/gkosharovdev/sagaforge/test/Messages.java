package io.github.gkosharovdev.sagaforge.test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Failure messages in Saga Forge's terms ({@code specs/009-saga-test-fixture} contracts/fixture-api.md, FR-014):
 *
 * <pre>
 * OrderProcessingSaga[orderId=o-1] (saga-1), when PaymentProcessedEvent[paymentId=payment-1, orderId=o-1, amount=10]:
 *   step processPayment completed (attempt 1), status RUNNING
 *   expected commands: [ReserveStockCommand[orderId=o-1]]
 *   dispatched:        []
 *   expected 1 command, dispatched none
 * </pre>
 */
final class Messages {

    private Messages() {
    }

    /** The header and situation lines for {@code subject} (or the event's handling if there is none). */
    static String situation(SagaFixture fixture, EventResult result, Optional<InstanceView> subject) {
        StringBuilder text = new StringBuilder();
        if (subject.isPresent()) {
            InstanceView instance = subject.get();
            text.append(instance.describe()).append(", when ").append(result.event()).append(":\n");
            text.append("  ").append(stepsLine(result, instance)).append('\n');
            List<String> compensations = compensationsRun(fixture, result, instance);
            if (!compensations.isEmpty()) {
                text.append("  compensations: ").append(String.join(", ", compensations)).append('\n');
            }
            instance.failure().ifPresent(failure -> text.append("  failure: ").append(describe(failure))
                    .append('\n'));
        } else {
            text.append("when ").append(result.event()).append(":\n");
            text.append("  ").append(handlingLine(result)).append('\n');
        }
        return text.toString();
    }

    /** {@code label: expected / actual} lines, aligned. */
    static String expectedActual(String expectedLabel, Object expected, String actualLabel, Object actual) {
        int width = Math.max(expectedLabel.length(), actualLabel.length()) + 1;
        return "  " + pad(expectedLabel + ":", width) + " " + expected + "\n"
                + "  " + pad(actualLabel + ":", width) + " " + actual + "\n";
    }

    /** {@code expected 1 command, dispatched none}. */
    static String commandCount(int expected, int actual) {
        return "  expected " + count(expected, "command") + ", dispatched " + (actual == 0 ? "none" : actual) + "\n";
    }

    /** The compensation methods that ran for {@code instance} during the event, in the order they ran. */
    static List<String> compensationsRun(SagaFixture fixture, EventResult result, InstanceView instance) {
        Map<String, StepRecord> before = result.before(instance.sagaId()).map(InstanceView::steps).orElse(Map.of());
        return instance.steps().entrySet().stream()
                .filter(entry -> ran(entry.getValue()) && !ran(before.get(entry.getKey())))
                .sorted(Comparator.comparingInt((Map.Entry<String, StepRecord> entry)
                        -> entry.getValue().completionSequence()).reversed())
                .map(entry -> fixture.compensationOf(instance.sagaType(), entry.getKey())
                        + (entry.getValue().compensation().status() == CompensationStatus.FAILED ? " (failed)" : ""))
                .toList();
    }

    /** The steps of {@code instance} that completed during the event. */
    static List<String> stepsCompleted(EventResult result, InstanceView instance) {
        Map<String, StepRecord> before = result.before(instance.sagaId()).map(InstanceView::steps).orElse(Map.of());
        List<String> completed = new ArrayList<>();
        instance.steps().entrySet().stream()
                .filter(entry -> entry.getValue().status() == StepStatus.COMPLETED)
                .filter(entry -> before.get(entry.getKey()) == null
                        || before.get(entry.getKey()).status() != StepStatus.COMPLETED)
                .sorted(Comparator.comparingInt(entry -> entry.getValue().completionSequence()))
                .forEach(entry -> completed.add(entry.getKey()));
        return completed;
    }

    static String describe(SagaFailure failure) {
        return "step " + failure.stepName() + ", " + failure.causeType()
                + (failure.message() == null ? "" : ": " + failure.message())
                + (failure.classification() == null ? "" : ", " + failure.classification())
                + ", " + count(Math.max(failure.attempts(), 1), "attempt");
    }

    static SagaExpectationError unsupportedContextValue(SagaFixture fixture, EventResult result,
            UnsupportedContextValueException rejected) {
        Optional<InstanceView> instance = fixture.current(rejected.sagaId());
        String step = result.reports().stream().flatMap(report -> report.results().stream())
                .filter(handled -> rejected.sagaId().equals(handled.sagaId()) && handled.failure() != null)
                .map(handled -> handled.failure().stepName())
                .findFirst().orElse("?");
        String header = instance.map(InstanceView::describe).orElse(Model.simpleName(rejected.sagaType())
                + " (" + rejected.sagaId() + ")");
        return new SagaExpectationError(header + ", when " + result.event() + ":\n"
                + "  step " + step + " stored SagaContext value '" + rejected.keyPath() + "' of type "
                + rejected.valueType() + ",\n"
                + "  which durable storage cannot persist (supported: " + DurableContextValues.SUPPORTED + ").\n"
                + "  Store it as a String (for example amount.toPlainString()), or build the fixture with"
                + " checkContextValues(false).", rejected);
    }

    private static String stepsLine(EventResult result, InstanceView instance) {
        List<String> parts = new ArrayList<>();
        for (String step : stepsCompleted(result, instance)) {
            parts.add("step " + step + " completed (attempt " + Math.max(instance.steps().get(step).attempts(), 1)
                    + ")");
        }
        if (parts.isEmpty()) {
            parts.add("no step completed");
        }
        String status = "status " + instance.status() + instance.outcomeStep().map(step -> " (" + step + ")")
                .orElse("");
        String outcome = result.reports().stream().flatMap(report -> report.results().stream())
                .filter(handled -> instance.sagaId().equals(handled.sagaId()))
                .map(handled -> "outcome " + handled.outcome())
                .reduce((first, second) -> second).orElse("");
        return String.join(", ", parts) + ", " + status + (outcome.isEmpty() ? "" : ", " + outcome);
    }

    private static String handlingLine(EventResult result) {
        HandlingReport report = result.reports().get(0);
        if (report.ignored()) {
            return "no saga type reacts to it";
        }
        List<String> parts = new ArrayList<>();
        for (SagaHandlingResult handled : report.results()) {
            parts.add(Model.simpleName(handled.sagaType()) + (handled.sagaId() == null ? "" : " (" + handled.sagaId()
                    + ")") + ": " + handled.outcome());
        }
        return String.join("; ", parts);
    }

    private static boolean ran(StepRecord step) {
        return step != null && step.compensation() != null
                && (step.compensation().status() == CompensationStatus.COMPENSATED
                        || step.compensation().status() == CompensationStatus.FAILED);
    }

    private static String count(int number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }
}
