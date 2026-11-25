package io.github.gkosharovdev.sagaforge.core.outcome;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import java.time.Instant;

/**
 * What started a saga instance's compensation, without the failure message: a {@code @StartCompensation} step
 * ({@link CompensationTriggerKind#FAILURE_OUTCOME}) or a step failure.
 *
 * <p>{@code kind}, {@code stepName} and {@code at} are required. For {@code FAILURE_OUTCOME} the failure components
 * are {@code null}; for a failure kind they are all present. {@code commandType} is present if and only if the kind is
 * {@code COMMAND_FAILED}. An {@link IllegalArgumentException} names the component that breaks the rule.</p>
 *
 * @param kind           what started compensation
 * @param stepName       the {@code @StartCompensation} step, the step whose failure started compensation, or the
 *                       step that sent the failed command
 * @param classification for a failure: {@code FINAL} or {@code EXHAUSTED}; otherwise {@code null}
 * @param causeType      for a failure: the fully qualified class name of the cause; otherwise {@code null}
 * @param attempts       for a failure: the attempts made, at least 1; otherwise {@code null}
 * @param at             when compensation started
 * @param commandType    for {@code COMMAND_FAILED}: the fully qualified class name of the failed outbox command;
 *                       otherwise {@code null} ({@code specs/012-outbox-command-failure-feedback})
 * @param deadlineName   for {@code DEADLINE_EXPIRED}: the expired deadline; otherwise {@code null}
 *                       ({@code specs/017-saga-deadlines})
 */
public record OutcomeTrigger(CompensationTriggerKind kind, String stepName, FailureClassification classification,
        String causeType, Integer attempts, Instant at, String commandType, String deadlineName) {

    /** Validates every component. */
    public OutcomeTrigger {
        Values.requireNonNull(kind, "kind");
        Values.requireText(stepName, "stepName");
        Values.requireNonNull(at, "at");
        if (kind == CompensationTriggerKind.FAILURE_OUTCOME || kind == CompensationTriggerKind.DEADLINE_EXPIRED) {
            if (classification != null || causeType != null || attempts != null) {
                throw new IllegalArgumentException("classification, causeType and attempts must be null for " + kind);
            }
        } else {
            Values.requireNonNull(classification, "classification");
            Values.requireText(causeType, "causeType");
            Values.requireNonNull(attempts, "attempts");
            if (attempts < 1) {
                throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
            }
        }
        if (kind == CompensationTriggerKind.COMMAND_FAILED) {
            Values.requireText(commandType, "commandType");
        } else if (commandType != null) {
            throw new IllegalArgumentException("commandType must be null unless kind is COMMAND_FAILED");
        }
        if (kind == CompensationTriggerKind.DEADLINE_EXPIRED) {
            Values.requireText(deadlineName, "deadlineName");
        } else if (deadlineName != null) {
            throw new IllegalArgumentException("deadlineName must be null unless kind is DEADLINE_EXPIRED");
        }
    }

    /**
     * The trigger as it was before {@code specs/017-saga-deadlines}: no deadline name, so any kind but
     * {@code DEADLINE_EXPIRED}.
     */
    public OutcomeTrigger(CompensationTriggerKind kind, String stepName, FailureClassification classification,
            String causeType, Integer attempts, Instant at, String commandType) {
        this(kind, stepName, classification, causeType, attempts, at, commandType, null);
    }

    /**
     * The trigger as it was before {@code specs/012-outbox-command-failure-feedback}: no command type, so any kind but
     * {@code COMMAND_FAILED}.
     */
    public OutcomeTrigger(CompensationTriggerKind kind, String stepName, FailureClassification classification,
            String causeType, Integer attempts, Instant at) {
        this(kind, stepName, classification, causeType, attempts, at, null, null);
    }

    /** The message-free summary of {@code trigger}. */
    public static OutcomeTrigger of(CompensationTrigger trigger) {
        Values.requireNonNull(trigger, "trigger");
        if (trigger.failure() == null) {
            return new OutcomeTrigger(trigger.kind(), trigger.stepName(), null, null, null, trigger.at(), null,
                    trigger.deadlineName());
        }
        return new OutcomeTrigger(trigger.kind(), trigger.stepName(), trigger.failure().classification(),
                trigger.failure().causeType(), trigger.failure().attempts(), trigger.at(), trigger.commandType());
    }
}
