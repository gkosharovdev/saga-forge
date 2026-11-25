package io.github.gkosharovdev.sagaforge.core.compensation;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import java.time.Instant;

/**
 * Why a saga instance entered compensation; recorded once and never changed ({@code specs/006-saga-compensation}
 * FR-004, FR-010, FR-017).
 *
 * <p>A {@code @SagaCompensation} method that declares a parameter of this type receives the persisted trigger, so its
 * business rules can depend on the cause (for example, refund in full on a rejected delivery). The triggering event
 * itself is not available: data a compensation needs from it travels through {@code SagaContext}.</p>
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a rule above is violated.</p>
 *
 * @param kind        what started compensation; non-null
 * @param stepName    the {@code @StartCompensation} step ({@code FAILURE_OUTCOME}), the step that failed, or the step
 *                    that sent the failed command ({@code COMMAND_FAILED}); non-blank
 * @param eventType   fully qualified class name of the event the {@code @StartCompensation} step handled; non-null if
 *                    and only if {@code kind == FAILURE_OUTCOME}
 * @param commandType fully qualified class name of the outbox command that failed; non-null if and only if
 *                    {@code kind == COMMAND_FAILED} ({@code specs/012-outbox-command-failure-feedback})
 * @param failure     the recorded failure, classified {@code FINAL} for {@code FINAL_FAILURE}, {@code EXHAUSTED} for
 *                    {@code RETRIES_EXHAUSTED}, and either for {@code COMMAND_FAILED}; its step is {@code stepName};
 *                    non-null if and only if {@code kind != FAILURE_OUTCOME}
 * @param at          when compensation started; non-null
 */
public record CompensationTrigger(CompensationTriggerKind kind, String stepName, String eventType,
        String commandType, SagaFailure failure, Instant at) {

    public CompensationTrigger {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }
        if (at == null) {
            throw new IllegalArgumentException("at must not be null");
        }
        boolean outcome = kind == CompensationTriggerKind.FAILURE_OUTCOME;
        requirePresentIff(eventType, outcome, "eventType", "kind is FAILURE_OUTCOME", kind);
        requirePresentIff(commandType, kind == CompensationTriggerKind.COMMAND_FAILED, "commandType",
                "kind is COMMAND_FAILED", kind);
        if (outcome == (failure != null)) {
            throw new IllegalArgumentException(
                    "failure must be non-null if and only if kind is not FAILURE_OUTCOME, but kind is " + kind);
        }
        if (failure != null) {
            requireClassification(kind, failure.classification());
            if (!failure.stepName().equals(stepName)) {
                throw new IllegalArgumentException("stepName " + stepName + " must be the failed step "
                        + failure.stepName());
            }
        }
    }

    /**
     * The trigger as it was before {@code specs/012-outbox-command-failure-feedback}: no command type.
     *
     * @param kind      what started compensation; not {@code COMMAND_FAILED}
     * @param stepName  the {@code @StartCompensation} step or the step that failed
     * @param eventType the handled event's type for {@code FAILURE_OUTCOME}, otherwise {@code null}
     * @param failure   the step failure, {@code null} for {@code FAILURE_OUTCOME}
     * @param at        when compensation started
     */
    public CompensationTrigger(CompensationTriggerKind kind, String stepName, String eventType, SagaFailure failure,
            Instant at) {
        this(kind, stepName, eventType, null, failure, at);
    }

    /**
     * @param stepName  the {@code @StartCompensation} step whose unit of work committed
     * @param eventType fully qualified class name of the event it handled
     * @param at        when compensation started
     * @return a {@code FAILURE_OUTCOME} trigger
     */
    public static CompensationTrigger failureOutcome(String stepName, String eventType, Instant at) {
        return new CompensationTrigger(CompensationTriggerKind.FAILURE_OUTCOME, stepName, eventType, null, null, at);
    }

    /**
     * @param failure the step failure, classified {@code FINAL} or {@code EXHAUSTED}
     * @param at      when compensation started
     * @return a {@code FINAL_FAILURE} or {@code RETRIES_EXHAUSTED} trigger
     * @throws IllegalArgumentException if {@code failure} is {@code null} or classified {@code TRANSIENT} or
     *                                  {@code UNCLASSIFIED}, which never start compensation (FR-006)
     */
    public static CompensationTrigger failed(SagaFailure failure, Instant at) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        CompensationTriggerKind kind = switch (failure.classification()) {
            case FINAL -> CompensationTriggerKind.FINAL_FAILURE;
            case EXHAUSTED -> CompensationTriggerKind.RETRIES_EXHAUSTED;
            case TRANSIENT, UNCLASSIFIED -> throw new IllegalArgumentException(
                    "a " + failure.classification() + " failure does not start compensation");
        };
        return new CompensationTrigger(kind, failure.stepName(), null, null, failure, at);
    }

    /**
     * A {@code COMMAND_FAILED} trigger for a transport's report. The recorded failure names the sending step, the
     * report's cause type, classification and attempts, and no message ({@code specs/012} FR-006).
     *
     * @param report the failed outbox command; must not be {@code null}
     * @param at     when compensation started
     * @return a {@code COMMAND_FAILED} trigger
     * @throws IllegalArgumentException if {@code report} is {@code null}
     */
    public static CompensationTrigger commandFailed(CommandFailure report, Instant at) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        String sender = report.senderName();
        SagaFailure failure = new SagaFailure(sender, report.causeType(), null, at, report.classification(),
                report.attempts());
        return new CompensationTrigger(CompensationTriggerKind.COMMAND_FAILED, sender, null, report.commandType(),
                failure, at);
    }

    private static void requirePresentIff(String value, boolean required, String component, String condition,
            CompensationTriggerKind kind) {
        if (required != (value != null)) {
            throw new IllegalArgumentException(
                    component + " must be non-null if and only if " + condition + ", but kind is " + kind);
        }
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(component + " must not be blank");
        }
    }

    private static void requireClassification(CompensationTriggerKind kind, FailureClassification classification) {
        boolean allowed = switch (kind) {
            case FINAL_FAILURE -> classification == FailureClassification.FINAL;
            case RETRIES_EXHAUSTED -> classification == FailureClassification.EXHAUSTED;
            case COMMAND_FAILED -> classification == FailureClassification.FINAL
                    || classification == FailureClassification.EXHAUSTED;
            case FAILURE_OUTCOME -> false;
        };
        if (!allowed) {
            String expected = kind == CompensationTriggerKind.COMMAND_FAILED ? "FINAL or EXHAUSTED"
                    : kind == CompensationTriggerKind.FINAL_FAILURE ? "FINAL" : "EXHAUSTED";
            throw new IllegalArgumentException("failure of a " + kind + " trigger must be classified " + expected
                    + ", but is " + classification);
        }
    }
}
