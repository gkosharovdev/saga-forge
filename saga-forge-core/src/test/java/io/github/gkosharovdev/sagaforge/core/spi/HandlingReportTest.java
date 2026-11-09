package io.github.gkosharovdev.sagaforge.core.spi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link SagaHandlingResult}, {@link HandlingReport} and {@link EventHandlingException} per
 * specs/004-saga-engine/data-model.md and contracts/event-receiver.md.
 */
class HandlingReportTest {

    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final String SAGA_ID = "s-1";
    private static final String UOW = "uow-1";
    private static final Object EVENT = new Object();
    private static final SagaFailure FAILURE =
            new SagaFailure("processPayment", "java.lang.IllegalStateException", "declined",
                    Instant.parse("2026-01-01T10:15:30Z"));

    private static SagaHandlingResult result(HandlingOutcome outcome, String outcomeStep, SagaFailure failure,
                                             List<Throwable> handoffFailures) {
        return new SagaHandlingResult(SAGA_TYPE, SAGA_ID, outcome, false, outcomeStep, failure, UOW,
                handoffFailures);
    }

    private static SagaHandlingResult waiting() {
        return result(HandlingOutcome.WAITING, null, null, List.of());
    }

    @Nested
    @DisplayName("SagaHandlingResult")
    class Result {

        @Test
        @DisplayName("every outcome is accepted with its consistent fields")
        void validOutcomes() {
            assertThatCode(() -> {
                result(HandlingOutcome.WAITING, null, null, List.of());
                result(HandlingOutcome.COMPLETED, "completeOrder", null, List.of());
                result(HandlingOutcome.FAILED, null, FAILURE, List.of());
                new SagaHandlingResult(SAGA_TYPE, null, HandlingOutcome.DUPLICATE_START, false, null, null, null,
                        List.of());
                new SagaHandlingResult(SAGA_TYPE, null, HandlingOutcome.UNMATCHED, false, null, null, null,
                        List.of());
                result(HandlingOutcome.UNEXPECTED, null, null, List.of());
                result(HandlingOutcome.NOT_HANDLED, null, null, List.of());
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null sagaType, outcome or handoffFailures is rejected")
        void nullsRejected() {
            assertThatThrownBy(() -> new SagaHandlingResult(null, SAGA_ID, HandlingOutcome.WAITING, false, null,
                    null, UOW, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> result(null, null, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> result(HandlingOutcome.WAITING, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("outcomeStep is required for COMPLETED and rejected for every other outcome")
        void outcomeStepPresentIffCompleted() {
            assertThatThrownBy(() -> result(HandlingOutcome.COMPLETED, null, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> result(HandlingOutcome.WAITING, "completeOrder", null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> result(HandlingOutcome.FAILED, "completeOrder", FAILURE, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("failure is required for FAILED and rejected for every other outcome")
        void failurePresentIffFailed() {
            assertThatThrownBy(() -> result(HandlingOutcome.FAILED, null, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> result(HandlingOutcome.NOT_HANDLED, null, FAILURE, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> result(HandlingOutcome.COMPLETED, "completeOrder", FAILURE, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("handoffFailures are allowed only for WAITING and COMPLETED, never with a null element")
        void handoffFailuresOnlyAfterCommit() {
            List<Throwable> failures = List.of(new IllegalStateException("broker down"));

            assertThatCode(() -> result(HandlingOutcome.WAITING, null, null, failures)).doesNotThrowAnyException();
            assertThatCode(() -> result(HandlingOutcome.COMPLETED, "completeOrder", null, failures))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> result(HandlingOutcome.FAILED, null, FAILURE, failures))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> result(HandlingOutcome.NOT_HANDLED, null, null, failures))
                    .isInstanceOf(IllegalArgumentException.class);
            List<Throwable> withNull = Arrays.asList(new IllegalStateException(), null);
            assertThatThrownBy(() -> result(HandlingOutcome.WAITING, null, null, withNull))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("handoffFailures is a defensive, unmodifiable copy")
        void handoffFailuresCopied() {
            List<Throwable> original = new ArrayList<>(List.of(new IllegalStateException("first")));
            SagaHandlingResult result = result(HandlingOutcome.WAITING, null, null, original);

            original.add(new IllegalStateException("second"));

            assertThat(result.handoffFailures()).hasSize(1);
            assertThatThrownBy(() -> result.handoffFailures().add(new IllegalStateException()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("HandlingReport")
    class Report {

        @Test
        @DisplayName("ignored() is true if and only if there are no results")
        void ignoredIffNoResults() {
            assertThat(new HandlingReport(EVENT, List.of()).ignored()).isTrue();
            assertThat(new HandlingReport(EVENT, List.of(waiting())).ignored()).isFalse();
        }

        @Test
        @DisplayName("null event, null results or a null result is rejected")
        void nullsRejected() {
            assertThatThrownBy(() -> new HandlingReport(null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HandlingReport(EVENT, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HandlingReport(EVENT, Arrays.asList(waiting(), null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("results is a defensive, unmodifiable copy")
        void resultsCopied() {
            List<SagaHandlingResult> original = new ArrayList<>(List.of(waiting()));
            HandlingReport report = new HandlingReport(EVENT, original);

            original.add(waiting());

            assertThat(report.results()).hasSize(1);
            assertThatThrownBy(() -> report.results().add(waiting()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("EventHandlingException")
    class HandlingException {

        @Test
        @DisplayName("exposes the report, the first cause as cause and the others as suppressed")
        void reportAndCauses() {
            HandlingReport report = new HandlingReport(EVENT, List.of(waiting()));
            RuntimeException first = new IllegalStateException("store down");
            RuntimeException second = new IllegalStateException("FAILED write failed");
            RuntimeException third = new IllegalStateException("lock wait aborted");

            EventHandlingException exception =
                    new EventHandlingException("not handled", report, List.of(first, second, third));

            assertThat(exception.report()).isSameAs(report);
            assertThat(exception).hasMessage("not handled");
            assertThat(exception.getCause()).isSameAs(first);
            assertThat(exception.getSuppressed()).containsExactly(second, third);
        }

        @Test
        @DisplayName("a null report, null or empty causes, or a null cause is rejected")
        void invalidArgumentsRejected() {
            HandlingReport report = new HandlingReport(EVENT, List.of());
            RuntimeException cause = new IllegalStateException();

            assertThatThrownBy(() -> new EventHandlingException("m", null, List.of(cause)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EventHandlingException("m", report, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EventHandlingException("m", report, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            List<Throwable> withNull = Arrays.asList(cause, null);
            assertThatThrownBy(() -> new EventHandlingException("m", report, withNull))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
