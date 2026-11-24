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
 * are {@code null}; for a failure kind they are all present. An {@link IllegalArgumentException} names the component
 * that breaks the rule.</p>
 *
 * @param kind           what started compensation
 * @param stepName       the {@code @StartCompensation} step, or the step whose failure started compensation
 * @param classification for a failure: {@code FINAL} or {@code EXHAUSTED}; otherwise {@code null}
 * @param causeType      for a failure: the fully qualified class name of the cause; otherwise {@code null}
 * @param attempts       for a failure: the attempts made, at least 1; otherwise {@code null}
 * @param at             when compensation started
 */
public record OutcomeTrigger(CompensationTriggerKind kind, String stepName, FailureClassification classification,
        String causeType, Integer attempts, Instant at) {

    /** Validates every component. */
    public OutcomeTrigger {
        Values.requireNonNull(kind, "kind");
        Values.requireText(stepName, "stepName");
        Values.requireNonNull(at, "at");
        if (kind == CompensationTriggerKind.FAILURE_OUTCOME) {
            if (classification != null || causeType != null || attempts != null) {
                throw new IllegalArgumentException("classification, causeType and attempts must be null for "
                        + "FAILURE_OUTCOME");
            }
        } else {
            Values.requireNonNull(classification, "classification");
            Values.requireText(causeType, "causeType");
            Values.requireNonNull(attempts, "attempts");
            if (attempts < 1) {
                throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
            }
        }
    }

    /** The message-free summary of {@code trigger}. */
    public static OutcomeTrigger of(CompensationTrigger trigger) {
        Values.requireNonNull(trigger, "trigger");
        if (trigger.failure() == null) {
            return new OutcomeTrigger(trigger.kind(), trigger.stepName(), null, null, null, trigger.at());
        }
        return new OutcomeTrigger(trigger.kind(), trigger.stepName(), trigger.failure().classification(),
                trigger.failure().causeType(), trigger.failure().attempts(), trigger.at());
    }
}
